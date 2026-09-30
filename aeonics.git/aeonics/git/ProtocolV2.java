package aeonics.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import aeonics.http.HttpException;
import aeonics.util.Tuples.Tuple;

/**
 * Handles wire protocol v2 requests and responses for the Git HTTP transport protocol.
 * Protocol v2 is negotiated via the Git-Protocol: version=2 request header.
 */
public class ProtocolV2
{
	/**
	 * Writes a delimiter packet ("0001") to the output stream.
	 * In protocol v2 the delimiter separates the command/capabilities section
	 * from the command arguments section.
	 *
	 * @param out the output stream
	 * @throws IOException if writing fails
	 */
	public static void writeDelim(OutputStream out) throws IOException
	{
		out.write("0001".getBytes(StandardCharsets.US_ASCII));
	}

	/**
	 * Encodes the Git protocol v2 capability advertisement returned for
	 * GET /info/refs?service=git-upload-pack when the client sends
	 * Git-Protocol: version=2.
	 * <p>
	 * No {@code # service=} preamble and no ref lines are included;
	 * only the version line, capability lines, and a flush packet.
	 *
	 * @return the full pkt-line encoded capability advertisement
	 */
	public static byte[] encodeCapabilityAdvertisement()
	{
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ProtocolV1.writePktLine(out, "version 2");
			ProtocolV1.writePktLine(out, "agent=aeonics-git/1.0");
			ProtocolV1.writePktLine(out, "ls-refs");
			ProtocolV1.writePktLine(out, "fetch=shallow");
			ProtocolV1.writePktLine(out, "object-format=sha1");
			ProtocolV1.writeFlush(out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			throw new RuntimeException("Failed to encode capability advertisement", e);
		}
	}

	/**
	 * Peeks at the capability section pkt-lines of a v2 request body to determine
	 * the command. Scans all lines before the {@code 0001} delimiter or {@code 0000}
	 * flush, so it correctly handles bodies where capability lines (e.g.
	 * {@code object-format=sha1}) precede the {@code command=} line.
	 *
	 * @param body the request body decoded as UTF-8
	 * @return the command name (e.g. {@code "ls-refs"} or {@code "fetch"}), or
	 *         {@code null} if no {@code command=} line is found in the capability section
	 */
	public static String parseCommand(String body)
	{
		if( body == null || body.length() < 4 ) return null;

		int i = 0;

		while( i + 4 <= body.length() )
		{
			int len;
			try { len = Integer.parseInt(body.substring(i, i + 4), 16); }
			catch (NumberFormatException e) { return null; }

			if( len == 0 || len == 1 ) break; // flush (0000) or delimiter (0001) — end of capability section
			if( i + len > body.length() ) return null;

			String line = body.substring(i + 4, i + len).trim();
			i += len;

			if( line.startsWith("command=") )
				return line.substring("command=".length());
		}

		return null;
	}

	/**
	 * Walks a pkt-line encoded body and returns the argument lines that follow
	 * the {@code 0001} delimiter (or start of body if no delimiter is present).
	 * <p>
	 * Phase 1: skip pkt-lines until a {@code 0001} delimiter or {@code 0000} flush
	 * is encountered — these are the command/capability lines.
	 * Phase 2: collect pkt-line payloads until the next {@code 0000} flush — these
	 * are the command argument lines.
	 *
	 * @param body    the request body decoded as UTF-8
	 * @param context a short label used in error messages (e.g. {@code "ls-refs"})
	 * @return the list of trimmed argument line strings
	 * @throws HttpException 400 if the pkt-line framing is corrupt
	 */
	private static List<String> parseArgLines(String body, String context)
	{
		List<String> args = new ArrayList<>();
		int i = 0;

		// --- Phase 1: skip command + capabilities section (up to 0001 delimiter) ---
		while( i < body.length() )
		{
			if( i + 4 > body.length() ) throw new HttpException(400, "Corrupted or invalid " + context + " request header");

			int len;
			try { len = Integer.parseInt(body.substring(i, i + 4), 16); }
			catch (NumberFormatException e) { throw new HttpException(400, "Corrupted or invalid " + context + " request header"); }

			if( len == 1 ) { i += 4; break; } // delimiter packet 0001
			if( len == 0 ) { i += 4; break; } // flush without delimiter — treat as end of section
			if( i + len > body.length() ) throw new HttpException(400, "Corrupted or invalid " + context + " request length");
			i += len; // skip command/capability lines
		}

		// --- Phase 2: collect argument lines (up to 0000 flush) ---
		while( i < body.length() )
		{
			if( i + 4 > body.length() ) throw new HttpException(400, "Corrupted or invalid " + context + " request args");

			int len;
			try { len = Integer.parseInt(body.substring(i, i + 4), 16); }
			catch (NumberFormatException e) { throw new HttpException(400, "Corrupted or invalid " + context + " request args"); }

			if( len == 0 ) { i += 4; break; } // flush packet — end of args
			if( len == 1 ) { i += 4; break; } // delimiter (unexpected here, but tolerate)
			if( i + len > body.length() ) throw new HttpException(400, "Corrupted or invalid " + context + " arg length");

			args.add(body.substring(i + 4, i + len).trim());
			i += len;
		}

		return args;
	}

	/**
	 * Decodes a protocol v2 ls-refs request body into its parsed arguments.
	 * <p>
	 * The request body format is:
	 * <ol>
	 *   <li>pkt-lines until {@code 0001} (delimiter) — command and client
	 *       capabilities, skipped</li>
	 *   <li>pkt-lines until {@code 0000} (flush) — command arguments:
	 *       {@code symrefs}, {@code peel}, and {@code ref-prefix &lt;value&gt;}</li>
	 * </ol>
	 *
	 * @param body the request body decoded as UTF-8
	 * @return {@code Tuple.of(symrefs, Tuple.of(peel, prefixes))}
	 */
	public static Tuple<Boolean, Tuple<Boolean, List<String>>> decodeLsRefsRequest(String body)
	{
		boolean symrefs = false;
		boolean peel = false;
		List<String> prefixes = new ArrayList<>();

		for( String line : parseArgLines(body, "ls-refs") )
		{
			if( line.equals("symrefs") )
				symrefs = true;
			else if( line.equals("peel") )
				peel = true;
			else if( line.startsWith("ref-prefix ") )
				prefixes.add(line.substring("ref-prefix ".length()));
		}

		return Tuple.of(symrefs, Tuple.of(peel, prefixes));
	}

	/**
	 * Encodes a protocol v2 ls-refs response.
	 * <p>
	 * For each ref in {@code refs}, this method applies the following rules:
	 * <ul>
	 *   <li>If {@code prefixes} is non-empty, only include refs whose name matches
	 *       at least one prefix (prefix matching is by string prefix).</li>
	 *   <li>{@code HEAD} is special: always included when no prefixes are set, or
	 *       when any prefix equals {@code "HEAD"} or starts with
	 *       {@code "refs/heads/"}.</li>
	 *   <li>Each ref line is {@code <sha> <refname>}, and when {@code symrefs} is
	 *       {@code true} and the ref is {@code HEAD}, the suffix
	 *       {@code symref-target:refs/heads/<headBranch>} is appended.</li>
	 * </ul>
	 *
	 * @param refs       the refs to advertise (ref name -> SHA hex)
	 * @param symrefs    whether to include symref targets
	 * @param peel       whether to include peeled tags (currently unused, reserved)
	 * @param prefixes   the ref prefixes the client requested
	 * @param headBranch the branch HEAD currently points to (e.g. {@code "main"})
	 * @return the full pkt-line encoded ls-refs response
	 */
	public static byte[] encodeLsRefsResponse(Map<String, String> refs, boolean symrefs, boolean peel, List<String> prefixes, String headBranch)
	{
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();

			boolean headPrefixAllowed = prefixes.isEmpty()
				|| prefixes.contains("HEAD")
				|| prefixes.stream().anyMatch(p -> p.startsWith("refs/heads/"));

			for( Map.Entry<String, String> entry : refs.entrySet() )
			{
				String name = entry.getKey();
				String sha = entry.getValue();

				boolean include;
				if( name.equals("HEAD") )
					include = headPrefixAllowed;
				else if( prefixes.isEmpty() )
					include = true;
				else
					include = prefixes.stream().anyMatch(name::startsWith);

				if( !include ) continue;

				StringBuilder line = new StringBuilder();
				line.append(sha).append(' ').append(name);

				if( symrefs && name.equals("HEAD") && headBranch != null && !headBranch.isEmpty() )
					line.append(" symref-target:refs/heads/").append(headBranch);

				ProtocolV1.writePktLine(out, line.toString());
			}

			ProtocolV1.writeFlush(out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			throw new RuntimeException("Failed to encode ls-refs response", e);
		}
	}

	/**
	 * Decodes a protocol v2 fetch request body into its parsed arguments.
	 * <p>
	 * The request body format is:
	 * <ol>
	 *   <li>pkt-lines until {@code 0001} (delimiter) — command and client
	 *       capabilities, skipped</li>
	 *   <li>pkt-lines until {@code 0000} (flush) — command arguments:
	 *       {@code want &lt;sha&gt;}, {@code have &lt;sha&gt;}, {@code done},
	 *       and capability flags such as {@code thin-pack}, {@code ofs-delta},
	 *       {@code no-progress}</li>
	 * </ol>
	 *
	 * @param body the request body decoded as UTF-8
	 * @return {@code Tuple.of(Tuple.of(wants, haves), Tuple.of(done, flags))}
	 */
	public static Tuple<Tuple<Set<String>, Set<String>>, Tuple<Boolean, Set<String>>> decodeFetchRequest(String body)
	{
		Set<String> wants = new LinkedHashSet<>();
		Set<String> haves = new LinkedHashSet<>();
		Set<String> flags = new LinkedHashSet<>();
		boolean done = false;

		for( String line : parseArgLines(body, "fetch") )
		{
			int space = line.indexOf(' ');

			if( line.startsWith("want ") )
			{
				if( space < 0 || line.length() - space - 1 < 40 ) throw new HttpException(400, "Corrupted or invalid want line");
				String sha = line.substring(space + 1, space + 41);
				if( !sha.matches("[a-f0-9]{40}") ) throw new HttpException(400, "Invalid SHA in want line");
				wants.add(sha);
			}
			else if( line.startsWith("have ") )
			{
				if( space < 0 || line.length() - space - 1 < 40 ) throw new HttpException(400, "Corrupted or invalid have line");
				String sha = line.substring(space + 1, space + 41);
				if( !sha.matches("[a-f0-9]{40}") ) throw new HttpException(400, "Invalid SHA in have line");
				haves.add(sha);
			}
			else if( line.equals("done") )
				done = true;
			else if( !line.isEmpty() )
				flags.add(line);
		}

		return Tuple.of(Tuple.of(wants, haves), Tuple.of(done, flags));
	}

	/**
	 * Encodes a protocol v2 fetch response.
	 * <p>
	 * When {@code done} is {@code false} (incremental negotiation phase), only the
	 * acknowledgments section is written — no packfile is included. When {@code done}
	 * is {@code true}, a complete packfile wrapped in side-band-64k is written.
	 * <p>
	 * Packfile format: {@code PACK} signature + version 2 (int) + object count (int)
	 * + per-object variable-length type+size header + zlib-compressed content
	 * + SHA-1 trailer. The packfile is then chunked into side-band-64k frames with
	 * band byte {@code 0x01}.
	 *
	 * @param result     the pull result: {@code result.a} is the ACK sha or
	 *                   {@code null}, {@code result.b} is the list of
	 *                   (type, content) object pairs
	 * @param done       whether the client sent {@code done} (packfile expected)
	 * @param noProgress whether to suppress progress output (currently unused)
	 * @return the full protocol v2 fetch response bytes
	 */
	public static byte[] encodeFetchResponse(Tuple<String, List<Tuple<String, byte[]>>> result, boolean done, boolean noProgress)
	{
		try
		{
			String match = result.a;
			List<Tuple<String, byte[]>> objects = result.b;

			ByteArrayOutputStream out = new ByteArrayOutputStream();

			if( !done )
			{
				// --- Incremental negotiation: acknowledgments only, no packfile ---
				ProtocolV1.writePktLine(out, "acknowledgments");
				if( match != null )
					ProtocolV1.writePktLine(out, "ACK " + match);
				else
					ProtocolV1.writePktLine(out, "NAK");
				ProtocolV1.writeFlush(out);
				return out.toByteArray();
			}

			// --- Done: build and send packfile ---
			ProtocolV1.writePktLine(out, "packfile");
			return ProtocolV1.encodePackResponse(out.toByteArray(), objects);
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to generate fetch response", e);
		}
	}
}
