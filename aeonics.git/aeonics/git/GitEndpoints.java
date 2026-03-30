package aeonics.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import aeonics.data.Data;
import aeonics.entity.Registry;
import aeonics.entity.Storage;
import aeonics.entity.security.Token;
import aeonics.entity.security.User;
import aeonics.http.Endpoint;
import aeonics.http.HttpException;
import aeonics.manager.Config;
import aeonics.manager.Logger;
import aeonics.manager.Manager;
import aeonics.manager.Security;
import aeonics.template.Parameter;
import aeonics.util.StringUtils;
import aeonics.util.Tuples.Triple;
import aeonics.util.Tuples.Tuple;

/**
 * Registers the three Git Smart HTTP endpoints for multi-repo support.
 * <p>
 * URL pattern: {@code {root}/{repoName}/info/refs}, {@code {root}/{repoName}/git-upload-pack},
 * {@code {root}/{repoName}/git-receive-pack} where root is configured via
 * {@code Manager.of(Config.class).get(Git.class, "root")}.
 * <p>
 * Repo lookup: the repo name is extracted from the URL path and resolved via
 * {@code Registry.of(GitRepo.class).get(repoName)}.
 */
public class GitEndpoints
{
	private GitEndpoints() {}

	/**
	 * Registers all three git endpoints. Called during the RUN phase.
	 */
	public static void register()
	{
		String root = Manager.of(Config.class).get(Git.class, "root").asString();
		if( root == null || root.isEmpty() ) root = "/git";

		registerRefs(root);
		registerUpload(root);
		registerReceive(root);
	}

	/**
	 * Authenticates a git request using Basic auth and validates the token scope.
	 *
	 * @param request the HTTP request data
	 * @return the authenticated user
	 * @throws HttpException if authentication fails
	 */
	private static User.Type authenticate(Data request)
	{
		if( request.get("headers").isEmpty("authorization") )
		{
			throw new HttpException(401, Data.map()
				.put("headers", Data.map().put("WWW-Authenticate", "Basic realm=\"restricted\"")));
		}

		String b64 = request.get("headers").asString("authorization");
		if( !b64.startsWith("Basic ") ) throw new HttpException(403, "Invalid authentication type");
		String[] raw = StringUtils.split(new String(Base64.getDecoder().decode(b64.substring(6)), StandardCharsets.UTF_8), ":");
		if( raw.length != 2 ) throw new HttpException(403, "Invalid authentication encoding");
		Token t = Manager.of(Security.class).authenticate(raw[1], true);
		if( t == null ) throw new HttpException(403, "Invalid token");

		String scope = Manager.of(Config.class).get(Git.class, "scope").asString();
		if( scope == null || scope.isEmpty() ) scope = "git";
		if( !t.inScope(scope) ) throw new HttpException(403, "Invalid scope");
		if( !t.user().login().equals(raw[0]) ) throw new HttpException(403, "Token mismatch");

		return t.user();
	}

	/**
	 * Extracts the repo name from the request URL path.
	 * Expected URL format: {root}/{repoName}/info/refs or {root}/{repoName}/git-upload-pack etc.
	 *
	 * @param url the full request URL path
	 * @param root the configured git root prefix
	 * @param suffix the expected path suffix (e.g. "/info/refs", "/git-upload-pack")
	 * @return the repo name
	 * @throws HttpException if the URL does not match the expected pattern
	 */
	private static String extractRepoName(String url, String root, String suffix)
	{
		// Strip root prefix and suffix to get repo name
		if( !url.startsWith(root) || !url.endsWith(suffix) )
			throw new HttpException(404, "Not found");

		String repoName = url.substring(root.length(), url.length() - suffix.length());
		// Remove leading/trailing slashes
		while( repoName.startsWith("/") ) repoName = repoName.substring(1);
		while( repoName.endsWith("/") ) repoName = repoName.substring(0, repoName.length() - 1);

		if( repoName.isEmpty() ) throw new HttpException(404, "No repository specified");
		return repoName;
	}

	/**
	 * Resolves a GitRepo entity by name from the registry.
	 *
	 * @param repoName the repository name/ID
	 * @return the GitRepo.Type entity
	 * @throws HttpException if the repo is not found or has no storage configured
	 */
	private static GitRepo.Type resolveRepo(String repoName)
	{
		GitRepo.Type repo = Registry.of(GitRepo.class).get(repoName);
		if( repo == null ) throw new HttpException(404, "Repository not found: " + repoName);
		if( repo.store() == null ) throw new HttpException(500, "Repository has no storage configured");
		return repo;
	}

	private static void registerRefs(String root)
	{
		new Endpoint.Rest() { }
			.template()
			.summary("Returns GIT refs")
			.description("This endpoint is part of the smart http git protocol and returns the refs requested by the client")
			.add(new Parameter("service")
				.summary("Service")
				.description("The requested service: 'git-upload-pack' or 'git-receive-pack'")
				.format(Parameter.Format.TEXT)
				.optional(false))
			.create()
			.<Endpoint.Rest.Type>cast()
			.process((data, user, request) ->
			{
				user = authenticate(request.content());

				String url = request.content().asString("url");
				String repoName = extractRepoName(url, root, "/info/refs");
				GitRepo.Type repo = resolveRepo(repoName);
				Storage.Type store = repo.store();
				String repoRoot = repo.root();

				synchronized(store)
				{
					if( !data.asString("service").equals("git-upload-pack") && !data.asString("service").equals("git-receive-pack") )
						throw new HttpException(400, "Invalid service request");

					String gitProtocol = request.content().get("headers").asString("git-protocol");
					boolean isV2 = gitProtocol != null && gitProtocol.contains("version=2");

					byte[] pack;
					if( isV2 && data.asString("service").equals("git-upload-pack") )
					{
						pack = ProtocolV2.encodeCapabilityAdvertisement();
					}
					else
					{
						Map<String, String> refs = Operations.refs(store, repoRoot);
						pack = ProtocolV1.encodeRefsResponse(data.asString("service"), refs);
					}

					return Data.map()
						.put("isHttpResponse", true)
						.put("code", 200)
						.put("body", new String(pack, StandardCharsets.ISO_8859_1))
						.put("headers", Data.map().put("content-encoding", null))
						.put("mime", data.asString("service").equals("git-upload-pack") ? "application/x-git-upload-pack-advertisement" : "application/x-git-receive-pack-advertisement");
				}
			})
			.url(root + "/*/info/refs")
			.method("GET");
	}

	private static void registerUpload(String root)
	{
		new Endpoint.Rest() { }
			.template()
			.summary("Returns GIT objects")
			.description("This endpoint is part of the smart http git protocol and returns the response to a fetch/pull command")
			.create()
			.<Endpoint.Rest.Type>cast()
			.process((data, user, request) ->
			{
				user = authenticate(request.content());

				String url = request.content().asString("url");
				String repoName = extractRepoName(url, root, "/git-upload-pack");
				GitRepo.Type repo = resolveRepo(repoName);
				Storage.Type store = repo.store();
				String repoRoot = repo.root();

				byte[] pack = null;

				synchronized(store)
				{
					try
					{
						if( !request.content().get("headers").asString("content-type").equals("application/x-git-upload-pack-request") )
							throw new HttpException(400, "Invalid mime type");

						String upload = request.content().get("body").asString();

						String gitProtocol = request.content().get("headers").asString("git-protocol");
						boolean isV2 = gitProtocol != null && gitProtocol.contains("version=2");

						if( isV2 )
						{
							String command = ProtocolV2.parseCommand(upload);
							if( "ls-refs".equals(command) )
							{
								Tuple<Boolean, Tuple<Boolean, List<String>>> args = ProtocolV2.decodeLsRefsRequest(upload);
								boolean symrefs = args.a;
								boolean peelArg = args.b.a;
								List<String> prefixes = args.b.b;

								Map<String, String> refs = Operations.refs(store, repoRoot);
								String head = store.getString(repoRoot + "/HEAD");
								if( head == null || !head.startsWith("ref: refs/heads/") )
									throw new HttpException(400, "Repository HEAD is not a valid symbolic ref");
								String headBranch = head.substring("ref: refs/heads/".length()).trim();

								pack = ProtocolV2.encodeLsRefsResponse(refs, symrefs, peelArg, prefixes, headBranch);
							}
							else if( "fetch".equals(command) )
							{
								Tuple<Tuple<Set<String>, Set<String>>, Tuple<Boolean, Set<String>>> fetchArgs = ProtocolV2.decodeFetchRequest(upload);

								Set<String> wants = fetchArgs.a.a;
								Set<String> haves = fetchArgs.a.b;
								boolean done = fetchArgs.b.a;
								boolean noProgress = fetchArgs.b.b.contains("no-progress");

								Tuple<String, List<Tuple<String, byte[]>>> objects = Operations.pull(
									store,
									repoRoot,
									wants,
									haves);

								pack = ProtocolV2.encodeFetchResponse(objects, done, noProgress);
							}
							else
							{
								throw new HttpException(400, "Unknown or unsupported v2 command: " + command);
							}
						}
						else
						{
							Tuple<Set<String>, Set<String>> list = ProtocolV1.decodeUploadRequest(upload);

							Tuple<String, List<Tuple<String, byte[]>>> objects = Operations.pull(
								store,
								repoRoot,
								list.a,
								list.b);

							pack = ProtocolV1.encodeUploadResponse(objects);
						}
					}
					catch(Exception e)
					{
						Manager.of(Logger.class).info(Git.class, e);
						try( ByteArrayOutputStream out = new ByteArrayOutputStream() )
						{
							ProtocolV1.writePktLine(out, "ERR " + (e instanceof HttpException ? ((HttpException)e).data : e.getMessage()));
							ProtocolV1.writeFlush(out);
							pack = out.toByteArray();
						}
						catch (IOException x){ /* this never happens */ }
					}

					return Data.map()
						.put("isHttpResponse", true)
						.put("code", 200)
						.put("body", new String(pack, StandardCharsets.ISO_8859_1))
						.put("headers", Data.map().put("content-encoding", null))
						.put("mime", "application/x-git-upload-pack-result");
				}
			})
			.url(root + "/*/git-upload-pack")
			.method("POST");
	}

	private static void registerReceive(String root)
	{
		new Endpoint.Rest() { }
			.template()
			.summary("Accepts GIT objects")
			.description("This endpoint is part of the smart http git protocol and accepts objects for a push command")
			.create()
			.<Endpoint.Rest.Type>cast()
			.process((data, user, request) ->
			{
				user = authenticate(request.content());

				String url = request.content().asString("url");
				String repoName = extractRepoName(url, root, "/git-receive-pack");
				GitRepo.Type repo = resolveRepo(repoName);
				Storage.Type store = repo.store();
				String repoRoot = repo.root();

				byte[] response = null;

				synchronized(store)
				{
					try
					{
						if( !request.content().get("headers").asString("content-type").equals("application/x-git-receive-pack-request") )
							throw new HttpException(400, "Invalid mime type");

						byte[] receive = request.content().get("body").asString().getBytes(StandardCharsets.ISO_8859_1);

						Tuple<Map<String, Tuple<String, String>>, List<Tuple<String, byte[]>>> list = ProtocolV1.decodeReceiveRequest(receive, store, repoRoot);
						Map<String, Tuple<String, String>> refs = list.a;
						List<Tuple<String, byte[]>> objects = list.b;

						Operations.push(
							store,
							repoRoot,
							refs,
							objects);

						// Compute affected files for the default branch and call onPush hook
						String defaultBranch = repo.branch();
						Tuple<String, String> branchRef = refs.get("refs/heads/" + defaultBranch);
						Set<Triple<String, byte[], byte[]>> affectedFiles = new HashSet<>();

						if( branchRef != null )
						{
							affectedFiles = Bare.affectedFiles(
								store,
								repoRoot,
								branchRef.a,
								branchRef.b);
						}

						String status = repo.onPush(refs, affectedFiles);

						if( status == null || status.isEmpty() )
						{
							if( branchRef == null )
								status = "No changes detected on '" + defaultBranch + "' branch.\nNothing to deploy.";
							else
								status = "Complete.";
						}

						response = ProtocolV1.encodeReceiveResponse(refs, status);
					}
					catch(Exception e)
					{
						Manager.of(Logger.class).info(Git.class, e);
						response = ProtocolV1.encodeReceiveError((e instanceof HttpException ? ((HttpException)e).data.toString() : e.getMessage()));
					}

					return Data.map()
						.put("isHttpResponse", true)
						.put("code", 200)
						.put("body", new String(response, StandardCharsets.ISO_8859_1))
						.put("headers", Data.map().put("content-encoding", null))
						.put("mime", "application/x-git-receive-pack-result");
				}
			})
			.url(root + "/*/git-receive-pack")
			.method("POST");
	}
}
