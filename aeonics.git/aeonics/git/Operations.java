package aeonics.git;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import aeonics.entity.Storage;
import aeonics.util.Tuples.Tuple;

/**
 * Represents higher order operations.
 *
 * <ul>
 *   <li><b>init</b>: create a new bare repository with an initial commit</li>
 *   <li><b>refs</b>: returns current refs</li>
 *   <li><b>push</b>: the client pushes data to us</li>
 *   <li><b>pull</b>: the client requests data from us</li>
 * </ul>
 */
public class Operations
{
	/**
	 * Initializes a new bare Git repository
	 *
	 * @param store the storage backend
	 * @param path the root path of the bare Git repository
	 */
	public static void init(Storage.Type store, String path)
	{
		path = Storage.normalize(path);
		synchronized( store )
		{
			path = Storage.normalize(path);
			if( store.containsEntry(path + "/HEAD") )
				throw new IllegalStateException("Repository already initialized");

			store.put(path + "/HEAD", "ref: refs/heads/main\n");
			store.put(path + "/config", "[core]\n"
				+ "\trepositoryformatversion = 0\n"
				+ "\tfilemode = true\n"
				+ "\tbare = true\n");
			store.put(path + "/refs/heads/main", "0000000000000000000000000000000000000000");
		}
	}

	/**
	 * Returns the branches, tags and HEAD refs.
	 *
	 * @param store the storage backend
	 * @param path the root path of the bare Git repository
	 * @return branches, tags and HEAD refs
	 */
	public static Map<String, String> refs(Storage.Type store, String path)
	{
		path = Storage.normalize(path);
		synchronized(store)
		{
			Map<String, String> refs = new HashMap<>();
			path = Storage.normalize(path);

			for( String file : store.list(path + "/refs/") )
				refs.put(file.substring(path.length()+1), store.getString(file).trim());

			String head = store.getString(path + "/HEAD");
			if( head == null || !head.startsWith("ref: "))
				throw new IllegalStateException("HEAD is not a symbolic ref: " + head);

			refs.put("HEAD", store.getString(path + "/refs/heads/" + Git.headBranch(head)).trim());

			return refs;
		}
	}

	/**
	 * Pushes refs and objects to the bare Git repository.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param refs the refs to update (ref name -> Tuple(oldSha, newSha))
	 * @param objects the list of Git objects (type, unwrapped content)
	 */
	public static void push(Storage.Type store, String root, Map<String, Tuple<String, String>> refs, List<Tuple<String, byte[]>> objects)
	{
		synchronized (store)
		{
			// Defensive pass: validate all ref updates before writing anything
			for( Map.Entry<String, Tuple<String, String>> entry : refs.entrySet() )
			{
				String ref = Storage.normalize(entry.getKey());
				if( !ref.matches("refs/(heads|tags)/[A-Za-z0-9._-]+") )
					throw new IllegalArgumentException("Invalid ref name: " + ref);

				String oldSha = entry.getValue().a;
				String newSha = entry.getValue().b;

				// Deletion case: skip validation
				if( "0000000000000000000000000000000000000000".equals(newSha) ) continue;

				// Validate new SHA format
				if( !newSha.matches("[a-f0-9]{40}") )
					throw new IllegalArgumentException("Invalid SHA: " + newSha);

				String path = root + "/" + ref;
				if( store.containsEntry(path) )
				{
					String current = store.getString(path).trim();
					if( !current.equals(oldSha) )
						throw new IllegalStateException("Version mismatch. Pull for updates first");
				}

				if( !Bare.isAncestor(store, root, oldSha, newSha, objects) )
					throw new IllegalStateException("Non-fast-forward update is not allowed for ref: " + ref + ". Previous sha: " + oldSha + " Next sha: " + newSha);
			}

			// Defensive pass: validate all object constraints before writing
			for( Tuple<String, byte[]> obj : objects )
			{
				switch(obj.a)
				{
					case "commit":
					{
						String text = new String(obj.b, StandardCharsets.UTF_8);
						if (!text.startsWith("tree ")) throw new IllegalArgumentException("Invalid commit: missing tree");
						if (!text.contains("\nauthor ")) throw new IllegalArgumentException("Invalid commit: missing author");
						if (!text.contains("\ncommitter ")) throw new IllegalArgumentException("Invalid commit: missing committer");
						if (!text.contains("\n\n")) throw new IllegalArgumentException("Invalid commit: missing message separator");
						break;
					}
					case "tree":
					{
						Bare.decodeTree(obj.b);
						break;
					}
					case "tag":
					{
						String text = new String(obj.b, StandardCharsets.UTF_8);
						if (!text.startsWith("object ")) throw new IllegalArgumentException("Invalid tag: missing object line");
						if (!text.contains("\ntype ")) throw new IllegalArgumentException("Invalid tag: missing type line");
						if (!text.contains("\ntagger ")) throw new IllegalArgumentException("Invalid tag: missing tagger");
						if (!text.contains("\n\n")) throw new IllegalArgumentException("Invalid tag: missing message separator");
						break;
					}
					default: break;
				}
			}

			// Write all objects
			Set<String> written = new HashSet<>();
			for( Tuple<String, byte[]> obj : objects )
				written.add(Bare.object(store, root, obj.a, obj.b));

			// Apply ref updates
			for( Map.Entry<String, Tuple<String, String>> entry : refs.entrySet() )
			{
				String ref = Storage.normalize(entry.getKey());
				String newSha = entry.getValue().b;
				String path = root + "/" + ref;

				if( "0000000000000000000000000000000000000000".equals(newSha) )
				{
					store.remove(path);
					continue;
				}

				// Refuse to update to unknown object
				if( !written.contains(newSha) && !store.containsEntry(Bare.sha2path(root, newSha)))
					throw new IllegalArgumentException("Missing object: " + newSha);

				store.put(path, newSha);
			}
		}
	}

	/**
	 * Flattens a bare Git repository to a single parentless commit containing the current HEAD tree.
	 * All history is discarded. Existing clones will need to re-clone after this operation.
	 *
	 * <p>The operation copies reachable objects to a temporary storage location, deletes the
	 * repository, then restores only the necessary objects and creates a fresh commit.</p>
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param tmp a temporary storage root (must be outside of {@code root})
	 */
	public static void flatten(Storage.Type store, String root, String tmp)
	{
		root = Storage.normalize(root);
		tmp = Storage.normalize(tmp);
		if( tmp.startsWith(root + "/") || root.startsWith(tmp + "/") || tmp.equals(root) )
			throw new IllegalArgumentException("Temporary path must not overlap with repository root");

		synchronized( store )
		{
			// Resolve HEAD branch and current tree
			String head = store.getString(root + "/HEAD");
			if( head == null || !head.startsWith("ref: ") )
				throw new IllegalStateException("HEAD is not a symbolic ref: " + head);
			String branch = Git.headBranch(head);

			String treeSha = Bare.latestTree(store, root, branch);
			if( treeSha == null )
				throw new IllegalStateException("No tree found at HEAD");

			// Collect all reachable object SHAs from the current tree
			Set<String> reachable = new HashSet<>();
			reachable.add(treeSha);
			Map<String, String> entries = Bare.listRecursive(store, root, treeSha, null);
			reachable.addAll(entries.values());

			// Copy reachable objects to temporary location
			for( String sha : reachable )
			{
				byte[] raw = store.get(Bare.sha2path(root, sha));
				if( raw == null )
					throw new IllegalStateException("Missing object during reset: " + sha);
				store.put(Bare.sha2path(tmp, sha), raw);
			}

			try
			{
				// Delete entire repository
				store.remove(root);

				// Restore objects from temporary location
				for( String sha : reachable )
				{
					byte[] raw = store.get(Bare.sha2path(tmp, sha));
					if( raw == null )
						throw new IllegalStateException("Missing object in temporary store: " + sha);
					store.put(Bare.sha2path(root, sha), raw);
				}

				// Recreate repository structure
				store.put(root + "/HEAD", "ref: refs/heads/" + branch + "\n");
				store.put(root + "/config", "[core]\n"
					+ "\trepositoryformatversion = 0\n"
					+ "\tfilemode = true\n"
					+ "\tbare = true\n");
				store.put(root + "/refs/heads/" + branch, "0000000000000000000000000000000000000000");

				// Create new parentless commit with the existing tree
				Bare.commit(store, root, treeSha, null, "Repository compacted", branch);
			}
			finally
			{
				// Clean up temporary location
				store.remove(tmp);
			}
		}
	}

	/**
	 * Resets a bare Git repository by wiping all content and reinitializing it.
	 * All history and files are permanently destroyed. Existing clones will need to re-clone.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 */
	public static void reset(Storage.Type store, String root)
	{
		root = Storage.normalize(root);
		synchronized( store )
		{
			store.remove(root);
			init(store, root);
		}
	}

	/**
	 * Minimal set = reachable(wants) minus reachable(haves).
	 * firstCommon = first commit encountered on any wants-path that belongs to the have-commit closure.
	 * - Treat only commits (and peeled tag targets) as frontier markers.
	 * - Include tag objects requested, but do not cross into targets that are already in the frontier.
	 * - Include all tree/subtree/blob objects for any included commit->s tree, unless already in have-object closure.
	 */
	public static Tuple<String, List<Tuple<String, byte[]>>> pull(Storage.Type store, String root, Set<String> wants, Set<String> haves)
	{
		for( String s : wants )
			if( !s.matches("[a-f0-9]{40}") )
				throw new IllegalArgumentException("Invalid SHA: " + s);
		for( String s : haves )
			if( !s.matches("[a-f0-9]{40}") )
				throw new IllegalArgumentException("Invalid SHA: " + s);

		Set<String> ancestors = new HashSet<>();
		Map<String, Tuple<String, byte[]>> delta = new HashMap<>();
		String ack = null;
		synchronized (store)
		{
			// retain only haves that point to commits
			haves.removeIf((sha) -> {
				Tuple<String, byte[]> object = Bare.object(store, root, sha);
				return object == null || !object.a.equals("commit");
			});

			// find ancestors of haves
			Deque<String> work = new ArrayDeque<>(haves);
			while( !work.isEmpty() )
			{
				String sha = work.pop();
				if( ancestors.contains(sha) ) continue;

				Tuple<String, byte[]> object = Bare.object(store, root, sha);
				if( object == null || !"commit".equals(object.a) ) continue;

				for( String parent : Bare.parseCommitHeaders(object.b).b )
				{
					if( ancestors.add(parent) ) work.push(parent);
				}
			}

			// add everything from the want until the first found have (if any)
			work = new ArrayDeque<>(wants);
			while( !work.isEmpty() )
			{
				String sha = work.pop();
				if( delta.containsKey(sha) ) continue;
				if( haves.contains(sha) ) { ack = sha; continue; }
				if( ancestors.contains(sha) ) continue;

				Tuple<String, byte[]> object = Bare.object(store, root, sha);
				if( object == null ) throw new IllegalArgumentException("Unknown requested object");

				switch(object.a)
				{
					case "tag":
					{
						String data = new String(object.b, StandardCharsets.ISO_8859_1);
						for( String line : data.split("\n") )
						{
							if( line.startsWith("object ") && line.length() >= 47 )
							{
								work.push(line.substring(7, 47));
								break;
							}
						}
						break;
					}
					case "commit":
					{
						// in case of branch that is merged, we can skip haves
						// so we need to check if this commit is an ancestor of any have

						Tuple<String, List<String>> h = Bare.parseCommitHeaders(object.b);
						if( h.a != null ) work.push(h.a);
						for( String p : h.b ) work.push(p);
						break;
					}
					case "tree":
					{
						for( int i = 0; i < object.b.length; )
						{
							while( i < object.b.length && object.b[i] != ' ') i++;
							if( i >= object.b.length ) break;
							i++;
							while( i < object.b.length && object.b[i] != 0) i++;
							if( i >= object.b.length ) break;
							i++;
							if( i + 20 > object.b.length) break;

							work.addLast(Bare.sha2hex(object.b, i));
							i += 20;
						}
						break;
					}
					case "blob":
					{
						break; // nothing to explore
					}
				}

				// add to the result
				delta.put(sha, object);
			}
		}

		return Tuple.of(ack, new ArrayList<>(delta.values()));
	}

}
