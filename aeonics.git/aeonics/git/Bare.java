package aeonics.git;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import aeonics.entity.Storage;
import aeonics.util.StringUtils;
import aeonics.util.Tuples.Quadruple;
import aeonics.util.Tuples.Triple;
import aeonics.util.Tuples.Tuple;

/**
 * A bare repository contains no working directory and consists only of Git's internal data:
 * <ul>
 *   <li><b>HEAD</b> — points to the default branch (e.g. {@code refs/heads/main}). This is static and never changes.</li>
 *   <li><b>objects/</b> — stores all Git objects: blobs (files), trees (directories), and commits.</li>
 *   <li><b>refs/</b> — contains named references to objects:
 *	 <ul>
 *	   <li>{@code refs/heads/} — branches (points to a commit object)</li>
 *	   <li>{@code refs/tags/} — tags (points to a tag or commit object)</li>
 *	 </ul>
 *   </li>
 * </ul>
 * <p>All data is content-addressed using SHA-1 object IDs. Commits reference trees, which reference blobs,
 * forming the full repository history and file structure.</p>
 *
 * <p>Object details</p>
 * <ul>
 *	<li><b>blob</b>: the complete file content</li>
 *	<li><b>tree</b>: points to blob objects (files in that directory), and other tree objects (subdirectories), and includes metadata</li>
 *	<li><b>commit</b>: one tree object, optional parent commit objects, and includes metadata</li>
 *	<li><b>tag</b>: points to a commit object and includes metadata</li>
 * </ul>
 */
public class Bare
{
	private static final Set<String> objectTypes = Set.of("blob", "tree", "commit", "tag");

	public static class TreeEntry
	{
		public String mode;
		public String name;
		public String sha;

		TreeEntry(String mode, String name, String sha)
		{
			this.mode = mode;
			this.name = name;
			this.sha = sha;
		}

		boolean isDir() { return mode.endsWith("40000"); }
	}

	/**
	 * Returns the list of branches in a bare Git repository.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @return a list of branch names
	 */
	public static List<String> branches(Storage.Type store, String root)
	{
		root = Storage.normalize(root);
		String path = root + "/refs/heads";
		return store.tree(path).stream()
			.filter(name -> !name.endsWith("/")) // skip directories
			.collect(Collectors.toList());
	}

	/**
	 * Returns the list of tags in a bare Git repository.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @return a list of branch names
	 */
	public static List<String> tags(Storage.Type store, String root)
	{
		root = Storage.normalize(root);
		String path = root + "/refs/tags";
		return store.tree(path).stream()
			.filter(name -> !name.endsWith("/")) // skip directories
			.collect(Collectors.toList());
	}

	/**
	 * Fetches a Git object content from the repository.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param type the Git object type ("blob", "tree", "commit", "tag")
	 * @param sha the SHA-1 object ID (40-character hex string)
	 * @return the raw unwrapped content, or null if the object is not found
	 */
	public static byte[] content(Storage.Type store, String root, String type, String sha)
	{
		if( type == null || !objectTypes.contains(type) )
			throw new IllegalArgumentException("Invalid object type: " + type);

		Tuple<String, byte[]> match = object(store, root, sha);
		if( match == null ) return null;

		if( !match.a.equals(type) )
			throw new IllegalArgumentException("Object type mismatch: expected " + type + ", found " + match.a);

		return match.b;
	}

	/**
	 * Fetches a Git object from the repository.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param sha the SHA-1 object ID (40-character hex string)
	 * @return a tuple with the object type and the raw unwrapped content, or null if the object is not found
	 */
	public static Tuple<String, byte[]> object(Storage.Type store, String root, String sha)
	{
		if( sha == null || sha.length() != 40 || !StringUtils.isHexa(sha) )
			throw new IllegalArgumentException("Invalid hash: " + sha);

		String path = sha2path(root, sha);

		byte[] zipped = store.get(path);
		if( zipped == null ) return null;

		try( InputStream raw = new InflaterInputStream(new ByteArrayInputStream(zipped)) )
		{
			ByteArrayOutputStream tmp = new ByteArrayOutputStream();
			int b;
			while( (b = raw.read()) != -1 && b != 0 )
				tmp.write(b);

			String header = new String(tmp.toByteArray(), StandardCharsets.UTF_8);
			String[] parts = header.split(" ");
			String type = parts[0];
			if( !objectTypes.contains(type) )
				throw new IllegalArgumentException("Invalid object type: " + type);

			int size = Integer.parseInt(parts[1]);
			byte[] body = raw.readAllBytes();
			if( body.length != size )
				throw new IllegalArgumentException("Object size mismatch: expected " + size + ", got " + body.length);

			return Tuple.of(type, body);
		}
		catch(Exception e)
		{
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	/**
	 * Lists all files and directories at the specified ref point (branch/tag).
	 * All paths are absolute path starting with "/".
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param ref the branch or tag name. It must be a valid branch or tag name. If null, the git HEAD is used.
	 * @return the list of all entries. Directories are suffixed by "/" character.
	 */
	public static List<String> list(Storage.Type store, String root, String ref)
	{
		String tree = latestTree(store, root, ref);
		if( tree == null ) throw new IllegalArgumentException("No tree in commit");

		List<String> list = new ArrayList<>();
		list.addAll(listRecursive(store, root, tree, null).keySet());
		return list;
	}

	/**
	 * Finds the sha of the first tree of the specified branch/tag
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param ref the branch or tag name. It must be a valid branch or tag name. If null, the git HEAD is used.
	 * @return the sha of the root tree object, or null if not found
	 */
	public static String latestTree(Storage.Type store, String root, String ref)
	{
		String sha = latestCommit(store, root, ref);
		if( sha == null ) return null;
		return commitTree(store, root, sha);
	}

	/**
	 * Finds the sha of the latest commit of the specified branch/tag
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param ref the branch or tag name. It must be a valid branch or tag name. If null, the git HEAD is used.
	 * @return the sha of the root tree object, or null if not found
	 */
	public static String latestCommit(Storage.Type store, String root, String ref)
	{
		root = Storage.normalize(root);
		if( ref == null || ref.isBlank() )
		{
			String head = store.getString(root + "/HEAD");
			if( head == null || !head.startsWith("ref: "))
				throw new IllegalStateException("HEAD is not a symbolic ref: " + head);
			ref = Git.headBranch(head);
		}

		String sha = store.getString(root + "/refs/heads/" + ref);
		if( sha == null ) sha = store.getString(root + "/refs/tags/" + ref);
		if( sha == null ) throw new IllegalArgumentException("Invalid ref: " + ref);
		if( sha.length() != 40 || !StringUtils.isHexa(sha) ) throw new IllegalArgumentException("Invalid ref sha: " + sha);
		if( sha.equals("0000000000000000000000000000000000000000") ) return null;

		// find the first commit it points to
		Tuple<String, byte[]> obj = object(store, root, sha);
		if( obj == null ) throw new IllegalArgumentException("Git object not found: " + sha);
		if( obj.a.equals("tag") )
		{
			// resolve the tag object
			String tag = new String(obj.b, StandardCharsets.ISO_8859_1);
			boolean found = false;
			for( String line : tag.split("\n") )
			{
				if( line.startsWith("object ") )
				{
					sha = line.substring(7).trim();
					obj = object(store, root, sha);
					found = true;
					break;
				}
			}
			if( !found ) throw new IllegalArgumentException("Invalid tag object: " + sha);
		}
		if( !obj.a.equals("commit") )
			throw new IllegalArgumentException("Ref does not point to a commit (found type: " + obj.a + ")");

		return sha;
	}

	/**
	 * Returns a recursive map of all directories and files with associated sha, starting from a tree sha.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param sha the recursion starting point tree sha
	 * @param prefix the path prefix of the current directory
	 * @return a recursive map of all directories and files with associated sha. Directories are suffixed with the '/' character.
	 */
	public static Map<String, String> listRecursive(Storage.Type store, String root, String sha, String prefix)
	{
		Tuple<String, byte[]> obj = object(store, root, sha);
		if( obj == null || !obj.a.equals("tree") )
			throw new IllegalArgumentException("Invalid tree object: " + sha);
		if( prefix == null || prefix.isBlank() ) prefix = "";

		Map<String, String> entries = new HashMap<>();

		for( TreeEntry te : decodeTree(obj.b) )
		{
			String path = prefix + "/" + te.name;
			if( te.isDir() )
			{
				entries.put(path + "/", te.sha);
				entries.putAll(listRecursive(store, root, te.sha, path));
			}
			else if( te.mode.startsWith("10") )
				entries.put(path, te.sha);
		}

		return entries;
	}

	/**
	 * Resolves the SHA of a file at the given path inside the specified branch or tag.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param path the file path to resolve
	 * @param ref the branch or tag name. It must be a valid branch or tag name. If null, the git HEAD is used.
	 * @return the SHA of the blob, or null if not found or not a file
	 */
	public static String findFile(Storage.Type store, String root, String path, String ref)
	{
		TreeEntry entry = findEntry(store, root, path, ref);
		if( entry == null || !entry.mode.startsWith("10") ) return null;
		return entry.sha;
	}

	/**
	 * Resolves the SHA of a folder and return the matching tree object
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param path the folder path to resolve
	 * @param ref the branch or tag name. It must be a valid branch or tag name. If null, the git HEAD is used.
	 * @return the SHA of the tree, or null if not found or not a folder
	 */
	public static String findFolder(Storage.Type store, String root, String path, String ref)
	{
		TreeEntry entry = findEntry(store, root, path, ref);
		if( entry == null || !entry.isDir() ) return null;
		return entry.sha;
	}

	/**
	 * Resolves the SHA of an entry and return the matching TreeEntry (mode, name, sha)
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param path the folder path to resolve
	 * @param ref the branch or tag name. It must be a valid branch or tag name. If null, the git HEAD is used.
	 * @return the TreeEntry, or null if not found
	 */
	public static TreeEntry findEntry(Storage.Type store, String root, String path, String ref)
	{
		path = Storage.normalize(path);

		String treeSha = latestTree(store, root, ref);
		if( treeSha == null ) return null;

		byte[] treeData = content(store, root, "tree", treeSha);
		if( treeData == null ) return null;

		LinkedList<String> parts = new LinkedList<>();
		for( String s : StringUtils.split(path, "/") ) parts.add(s);

		while( !parts.isEmpty() )
		{
			String target = parts.removeFirst();
			List<TreeEntry> entries = decodeTree(treeData);

			boolean matched = false;
			for( TreeEntry te : entries )
			{
				if( !target.equals(te.name) ) continue;
				if( parts.isEmpty() ) return te;

				if( !te.isDir() ) return null;

				treeData = content(store, root, "tree", te.sha);
				if( treeData == null ) throw new IllegalArgumentException("Corrupted tree: entry not a tree");
				matched = true;
				break;
			}
			if( !matched ) return null;
		}

		return null;
	}

	/**
	 * Fetches a file content as it is at the head
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param path the file path
	 * @return the file content or null if the file does not exist
	 */
	public static byte[] file(Storage.Type store, String root, String path) { return file(store, root, path, null); }

	/**
	 * Fetches a file content as it was at the specified ref point (branch/tag)
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param path the file path
	 * @param ref the branch or tag name. It must be a valid branch or tag name. If null, the git HEAD is used.
	 * @return the file content or null if the file does not exist
	 */
	public static byte[] file(Storage.Type store, String root, String path, String ref)
	{
		String sha = findFile(store, root, path, ref);
		if( sha == null ) return null;

		return content(store, root, "blob", sha);
	}

	/**
	 * Writes a Git object (blob, tree, commit, or tag) to the repository.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param type the Git object type ("blob", "tree", "commit", "tag")
	 * @param content the raw unwrapped content (not including header)
	 * @return the SHA-1 object ID (40-character hex string)
	 */
	public static String object(Storage.Type store, String root, String type, byte[] content)
	{
		synchronized(store)
		{
			if( type == null || !objectTypes.contains(type) )
				throw new IllegalArgumentException("Invalid object type: " + type);

			if( content == null ) content = new byte[0];

			try
			{
				MessageDigest sha = MessageDigest.getInstance("SHA-1");
				ByteArrayOutputStream zipped = new ByteArrayOutputStream();

				try( OutputStream zip = new DeflaterOutputStream(zipped) )
				{
					// Digest and compress header
					byte[] header = (type + " " + content.length + "\0").getBytes(StandardCharsets.UTF_8);
					sha.update(header);
					zip.write(header);

					// Digest and compress content
					sha.update(content);
					zip.write(content);
				}

				String shahex = sha2hex(sha.digest());
				String path = sha2path(root, shahex);

				if( store.containsEntry(path) )
				{
					// unlikely but can happen.
					// if the content is the same, fine.
					// else fail
					if( !Arrays.equals(zipped.toByteArray(), store.get(path)) )
						throw new IllegalStateException("Object hash clash");
				}
				else
					store.put(path, zipped.toByteArray());

				return shahex;
			}
			catch(Exception e)
			{
				throw new RuntimeException(e.getMessage(), e);
			}
		}
	}

	static String sha2path(String root, String sha)
	{
		String dir = sha.substring(0, 2);
		String file = sha.substring(2);
		String path = root + "/objects/" + dir + "/" + file;

		return path;
	}

	private static final char[] hexArray = "0123456789abcdef".toCharArray();
	static String sha2hex(byte[] sha) { return sha2hex(sha, 0); }
	static String sha2hex(byte[] sha, int offset)
	{
		if( sha.length - offset < 20 ) throw new IllegalArgumentException("Invalid raw SHA");
		char[] hexChars = new char[40];
		for( int i = 0; i < 20; i++ )
		{
			int v = sha[offset + i] & 0xFF;
			hexChars[i * 2] = hexArray[v >>> 4];
			hexChars[i * 2 + 1] = hexArray[v & 0x0F];
		}
		return new String(hexChars);
	}

	/**
	 * Creates a new branch pointing to the head.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param name the name of the new branch to create
	 */
	public static void createBranch(Storage.Type store, String root, String name) { createBranch(store, root, name, null); }

	/**
	 * Creates a new branch pointing to the latest commit of the specified reference,
	 * or to HEAD if no reference is provided.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param name the name of the new branch to create
	 * @param ref the source branch or tag name (null to use HEAD)
	 */
	public static void createBranch(Storage.Type store, String root, String name, String ref)
	{
		root = Storage.normalize(root);
		name = Storage.normalize(name);
		synchronized(store)
		{
			if( store.containsEntry(root + "/refs/heads/" + name) )
				throw new IllegalArgumentException("Duplicate branch name");
			if( store.containsEntry(root + "/refs/tags/" + name) )
				throw new IllegalArgumentException("Tag already exists with this name. Branch name must be unique.");

			String sha = latestCommit(store, root, ref);
			if( sha == null ) sha = "0000000000000000000000000000000000000000";
			store.put(root + "/refs/heads/" + name, sha);
		}
	}

	/**
	 * Creates a new tag pointing to the head.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param name the name of the new tag to create
	 */
	public static void createTag(Storage.Type store, String root, String name) { createTag(store, root, name, null); }

	/**
	 * Creates a new tag pointing to the latest commit of the specified reference,
	 * or to HEAD if no reference is provided.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param name the name of the new tag to create
	 * @param ref the source branch or tag name (null to use HEAD)
	 */
	public static void createTag(Storage.Type store, String root, String name, String ref)
	{
		root = Storage.normalize(root);
		name = Storage.normalize(name);
		synchronized(store)
		{
			if( store.containsEntry(root + "/refs/tags/" + name) )
				throw new IllegalArgumentException("Duplicate tag name");
			if( store.containsEntry(root + "/refs/heads/" + name) )
				throw new IllegalArgumentException("Branch already exists with this name. Tag name must be unique.");

			String sha = latestCommit(store, root, ref);
			if( sha == null ) throw new IllegalArgumentException("Nothing to tag. Commit something first.");
			store.put(root + "/refs/tags/" + name, sha);
		}
	}

	/**
	 * Renames a branch.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param oldName the current branch name
	 * @param newName the new branch name
	 */
	public static void renameBranch(Storage.Type store, String root, String oldName, String newName)
	{
		synchronized(store)
		{
			createBranch(store, root, newName, oldName);
			removeBranch(store, root, oldName);
		}
	}

	/**
	 * Renames a tag.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param oldName the current tag name
	 * @param newName the new tag name
	 */
	public static void renameTag(Storage.Type store, String root, String oldName, String newName)
	{
		synchronized(store)
		{
			createTag(store, root, newName, oldName);
			removeTag(store, root, oldName);
		}
	}

	/**
	 * Removes a branch.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param name the branch name
	 */
	public static void removeBranch(Storage.Type store, String root, String name)
	{
		root = Storage.normalize(root);
		name = Storage.normalize(name);
		synchronized(store)
		{
			store.remove(root + "/refs/heads/" + name);
		}
	}

	/**
	 * Removes a tag.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param name the tag name
	 */
	public static void removeTag(Storage.Type store, String root, String name)
	{
		root = Storage.normalize(root);
		name = Storage.normalize(name);
		synchronized(store)
		{
			store.remove(root + "/refs/tags/" + name);
		}
	}

	/**
	 * Creates a new commit in the specified branch (or HEAD) for the specified file.
	 * If the file already exists, its content is updated, else it is created.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param path the file path
	 * @param content the file content
	 * @param author the author attributions
	 * @param message the commit message
	 * @param branch the target branch (null or empty implies HEAD)
	 */
	public static void createFile(Storage.Type store, String root, String path, byte[] content, String author, String message, String branch)
	{
		synchronized(store)
		{
			path = Storage.normalize(path);
			String latestTreeSha = latestTree(store, root, branch);

			// Create the object for the file itself
			String newBlobSha = object(store, root, "blob", content);

			// Rebuild the tree including the file
			LinkedList<String> paths = new LinkedList<>();
			for( String s : StringUtils.split(path, "/") ) paths.add(s);
			String newTreeSha = rebuildTreeInsert(store, root, latestTreeSha, newBlobSha, paths);

			// Save the commit
			commit(store, root, newTreeSha, author, message, branch);
		}
	}

	/**
	 * Creates a new commit with the specified tree for the specified branch (or HEAD).
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param treeSha the new tree sha
	 * @param author the author attributions
	 * @param message the commit message
	 * @param branch the target branch (null or empty implies HEAD)
	 */
	public static void commit(Storage.Type store, String root, String treeSha, String author, String message, String branch)
	{
		root = Storage.normalize(root);
		branch = Storage.normalize(branch);

		// sanitize author
		if( author != null ) author = author.trim();
		if( author == null || author.isBlank() )
			author = "System <system@local>";
		if( author.indexOf('<') <= 0 || author.indexOf('<') != author.lastIndexOf('<') ||
			author.indexOf('>') < 0  || author.indexOf('>') != author.lastIndexOf('>') ||
			author.indexOf('>') < author.indexOf('<') ||  author.indexOf('>') != (author.length() - 1))
			throw new IllegalStateException("Invalid commit author");

		synchronized(store)
		{
			String latestCommitSha = latestCommit(store, root, branch);

			// Build commit with the new tree
			StringBuilder commitStr = new StringBuilder();
			commitStr.append("tree ").append(treeSha).append("\n");
			if( latestCommitSha != null )
				commitStr.append("parent ").append(latestCommitSha).append("\n");

			String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
			String timezone = "+0000";
			if( message == null || message.isBlank() ) message = "autocommit";

			commitStr.append("author ").append(author).append(" ").append(timestamp).append(" ").append(timezone).append("\n");
			commitStr.append("committer ").append(author).append(" ").append(timestamp).append(" ").append(timezone).append("\n");
			commitStr.append("\n");
			commitStr.append(message).append("\n");

			String commitSha = object(store, root, "commit", commitStr.toString().getBytes(StandardCharsets.UTF_8));

			// Update branch ref
			if( branch == null || branch.isBlank() )
			{
				String head = store.getString(root + "/HEAD");
				if( head == null || !head.startsWith("ref: "))
					throw new IllegalStateException("HEAD is not a symbolic ref: " + head);
				branch = Git.headBranch(head);
			}

			store.put(root + "/refs/heads/" + branch, commitSha);
		}
	}

	/**
	 * Checks if a commit SHA is an ancestor (down the history) of the provided commit SHA.
	 * This can be used to check the validity of the chain.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param ancestor the old SHA
	 * @param descendant the new SHA
	 * @return true if the chain is complete
	 */
	static boolean isAncestor(Storage.Type store, String root, String ancestor, String descendant) { return isAncestor(store, root, ancestor, descendant, Collections.emptyList()); }

	/**
	 * Checks if a commit SHA is an ancestor (down the history) of the provided commit SHA.
	 * This can be used to check the validity of the chain.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param ancestor the old SHA
	 * @param descendant the new SHA
	 * @param objects the list of temporary objects (not written) to include in the ancestor lookup
	 * @return true if the chain is complete
	 */
	static boolean isAncestor(Storage.Type store, String root, String ancestor, String descendant, List<Tuple<String, byte[]>> objects)
	{
		Deque<String> queue = new ArrayDeque<>();
		Set<String> visited = new HashSet<>();
		queue.add(descendant);

		while( !queue.isEmpty() )
		{
			String sha = queue.poll();
			if( !visited.add(sha) ) continue;
			if( sha.equals(ancestor) ) return true;

			Tuple<String, byte[]> obj = object(store, root, sha);

			// if not present, then lookup in local objects
			if( obj == null )
			{
				try
				{
					MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
					for( Tuple<String, byte[]> object : objects )
					{
						if( !"commit".equals(object.a) ) continue;
						sha1.update((object.a + " " + object.b.length + "\0").getBytes());
						sha1.update(object.b);
						if( sha.equals(sha2hex(sha1.digest())) )
						{
							obj = object;
							break;
						}
					}
				}
				catch(Exception e)
				{
					throw new RuntimeException("Hash computation failed", e);
				}
			}

			if( obj == null || !"commit".equals(obj.a) ) continue;

			queue.addAll(parseCommitHeaders(obj.b).b);
		}

		return false;
	}

	/**
	 * Rebuilds the tree files for the affected parts of the path to insert a new object blob
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param treeSha the current tree sha
	 * @param blobSha the modified file blob
	 * @param parts the path parts to the file
	 * @return the new tree sha
	 */
	private static String rebuildTreeInsert(Storage.Type store, String root, String treeSha, String newBlobSha, LinkedList<String> parts)
	{
		String name = parts.removeFirst();
		List<TreeEntry> entries = (treeSha == null ? new ArrayList<>() : decodeTree(content(store, root, "tree", treeSha)));

		if( parts.isEmpty() )
		{
			// this is the file itself
			int fileIdx = findEntryIndex(entries, name);
			boolean found = fileIdx >= 0;
			if( found ) entries.get(fileIdx).sha = newBlobSha;
			if( !found )
				entries.add(new TreeEntry("100644", name, newBlobSha));
		}
		else
		{
			// this is an intermediate directory
			int idx = findDirIndex(entries, name);
			boolean found = idx >= 0;
			if( found ) entries.get(idx).sha = rebuildTreeInsert(store, root, entries.get(idx).sha, newBlobSha, parts);
			if( !found )
			{
				String subtreeSha = rebuildTreeInsert(store, root, null, newBlobSha, parts);
				entries.add(new TreeEntry("40000", name, subtreeSha));
			}
		}

		return writeTree(store, root, entries);
	}

	/**
	 * Decodes the content of the binary tree format
	 *
	 * @param data the tree binary content (inflated, without header)
	 * @return the list of entries in the tree.
	 */
	static List<TreeEntry> decodeTree(byte[] data)
	{
		LinkedList<TreeEntry> entries = new LinkedList<>();

		int i = 0, mark = 0;
		while( i < data.length )
		{
			// =========== PARSE FILE MODE
			mark = i;
			while( data[i] != ' ' ) i++;
			String mode = new String(data, mark, i - mark, StandardCharsets.US_ASCII);
			if( !mode.equals("100644") && !mode.equals("100755") && !mode.equals("040000") && !mode.equals("40000") )
				throw new IllegalArgumentException("Illegal tree object mode: " + mode);
			i++; // skip space
			if( mode.equals("040000") ) mode = "40000"; // git writes directories as 40000, trees written by older versions use 040000

			// =========== PARSE FILE NAME
			mark = i;
			while( data[i] != 0 ) i++;
			String entryName = new String(data, mark, i - mark, StandardCharsets.ISO_8859_1);
			i++; // skip \0

			// =========== PARSE FILE SHA
			if( i + 20 > data.length) throw new IllegalArgumentException("Corrupted tree object");
			String sha = sha2hex(data, i);
			i += 20;

			entries.add(new TreeEntry(mode, entryName, sha));
		}

		return entries;
	}

	/**
	 * Parses a commit object's header to extract the tree SHA and parent SHAs.
	 * @param commitData the raw commit object bytes
	 * @return a Tuple of (treeSha, list of parentShas)
	 */
	static Tuple<String, List<String>> parseCommitHeaders(byte[] commitData)
	{
		String tree = null;
		List<String> parents = new ArrayList<>();
		for( String line : new String(commitData, StandardCharsets.UTF_8).split("\n") )
		{
			if( line.startsWith("tree ") ) tree = line.substring(5).trim();
			else if( line.startsWith("parent ") ) parents.add(line.substring(7).trim());
			else if( line.isBlank() ) break;
		}
		return Tuple.of(tree, parents);
	}

	/**
	 * Encodes entries into binary tree format
	 *
	 * @param entries the tree entries
	 * @return the binary tree content (inflated, without header)
	 */
	private static byte[] encodeTree(List<TreeEntry> entries)
	{
		entries.sort(Comparator.comparing(e -> e.isDir() ? e.name + "/" : e.name));

		ByteArrayOutputStream data = new ByteArrayOutputStream();
		try
		{
			for (TreeEntry entry : entries)
			{
				data.write((entry.mode + " " + entry.name).getBytes(StandardCharsets.UTF_8));
				data.write(0);
				for (int i = 0; i < 40; i += 2)
					data.write(Integer.parseInt(entry.sha.substring(i, i + 2), 16));
			}
		}
		catch(Exception e) { throw new RuntimeException(e); }

		return data.toByteArray();
	}

	/**
	 * Encodes tree entries and writes them as a git tree object.
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param entries the tree entries (sorted automatically by encodeTree)
	 * @return the SHA of the new tree object
	 */
	private static String writeTree(Storage.Type store, String root, List<TreeEntry> entries)
	{
		return object(store, root, "tree", encodeTree(entries));
	}

	private static int findEntryIndex(List<TreeEntry> entries, String name)
	{
		for( int i = 0; i < entries.size(); i++ )
			if( entries.get(i).name.equals(name) ) return i;
		return -1;
	}

	private static int findDirIndex(List<TreeEntry> entries, String name)
	{
		for( int i = 0; i < entries.size(); i++ )
			if( entries.get(i).isDir() && entries.get(i).name.equals(name) ) return i;
		return -1;
	}

	/**
	 * Removes a file or folder from the specified branch (or HEAD) and creates a new commit reflecting the deletion.
	 * If the path or its parent directories do not exist, this is a no-op (no commit is made).
	 *
	 * @param store   the storage backend
	 * @param root    the root path of the bare Git repository
	 * @param path    the file or folder path to remove
	 * @param author  the author attributions
	 * @param message the commit message
	 * @param branch  the target branch (null or empty implies HEAD)
	 */
	public static void remove(Storage.Type store, String root, String path, String author, String message, String branch)
	{
		synchronized (store)
		{
			path = Storage.normalize(path);
			if( path == null || path.isBlank() ) return;

			String latestTreeSha = latestTree(store, root, branch);
			if( latestTreeSha == null ) return; // branch not found -> no-op

			// Split path
			LinkedList<String> parts = new LinkedList<>();
			for( String s : StringUtils.split(path, "/") ) parts.add(s);
			if( parts.isEmpty() ) return;

			// Walk from root to parent of target, collecting spine entries
			// Each item: Tuple<dirNameOrNullAtRoot, entries>
			LinkedList<Tuple<String, List<TreeEntry>>> spine = new LinkedList<>();
			spine.add(Tuple.of(null, decodeTree(object(store, root, latestTreeSha).b))); // root entries

			for( int d = 0; d < parts.size() - 1; d++ )
			{
				String seg = parts.get(d);
				List<TreeEntry> parentEntries = spine.getLast().b;
				int dirIdx = findDirIndex(parentEntries, seg);
				TreeEntry dir = dirIdx >= 0 ? parentEntries.get(dirIdx) : null;
				if( dir == null )
				{
					// parent directory in the path does not exist -> no-op
					return;
				}
				spine.add(Tuple.of(seg, decodeTree(object(store, root, dir.sha).b)));
			}

			// Target leaf name and parent entries (root if depth == 1)
			String leaf = parts.getLast();
			List<TreeEntry> parentEntries = spine.getLast().b;

			// Find target entry (file or dir) in parent
			int leafPos = findEntryIndex(parentEntries, leaf);
			if( leafPos < 0 ) return; // target not present -> no-op

			// Remove the leaf entry
			parentEntries.remove(leafPos);

			// Special case: parent is root and we just edited it directly
			if( spine.size() == 1 )
			{
				String newRootTreeSha = writeTree(store, root, parentEntries);
				commit(store, root, newRootTreeSha, author, message, branch);
				return;
			}

			// Otherwise, bubble up with pruning
			boolean prune = parentEntries.isEmpty(); // if empty, remove this directory from its parent too (except root)
			String childSha = null;

			// If not empty, write the updated parent tree now
			if( !prune )
			{
				childSha = writeTree(store, root, parentEntries);
			}

			// Walk ancestors from parent-of-leaf up to ROOT (level 0)
			for( int level = spine.size() - 2; level >= 0; level-- )
			{
				List<TreeEntry> entries = new ArrayList<>(spine.get(level).b);
				String childName = spine.get(level + 1).a; // the name of the directory at the next level down (never null here)

				// Locate that child in this ancestor
				int idx = findDirIndex(entries, childName);
				if( idx < 0 ) return; // path broken above -> abort without commit (no writes have been persisted except trees without a commit)

				if( prune )
				{
					// Remove now-empty child directory
					entries.remove(idx);

					if( entries.isEmpty() )
					{
						if( level == 0 )
						{
							// ROOT: cannot prune away the root; write empty root tree
							childSha = writeTree(store, root, entries);
							prune = false; // stop pruning
						}
						else
						{
							// keep pruning upward; no tree write at this level
							childSha = null;
							prune = true;
						}
					}
					else
					{
						childSha = writeTree(store, root, entries);
						prune = false; // pruning stops here
					}
				}
				else
				{
					// Replace child's SHA with the updated subtree SHA
					TreeEntry e = entries.get(idx);
					entries.set(idx, new TreeEntry(e.mode, e.name, childSha));
					childSha = writeTree(store, root, entries);
				}
			}

			String newRootTreeSha = childSha; // set by the loop; guaranteed non-null because root is always written
			commit(store, root, newRootTreeSha, author, message, branch);
		}
	}


	/**
	 * Renames (moves) an existing file or folder within the same branch, creating a new commit.
	 *
	 * @param store   storage backend
	 * @param root    bare repo root
	 * @param oldPath current path (file or folder)
	 * @param newPath destination path (must not exist)
	 * @param author  author
	 * @param message commit message
	 * @param branch  target branch (null/empty => HEAD)
	 */
	public static void rename(Storage.Type store, String root, String oldPath, String newPath, String author, String message, String branch)
	{
		synchronized (store)
		{
			oldPath = Storage.normalize(oldPath);
			newPath = Storage.normalize(newPath);
			if( Objects.equals(oldPath, newPath) ) return;

			String latestTreeSha = latestTree(store, root, branch);
			if( latestTreeSha == null ) throw new IllegalArgumentException("Branch not found");

			// Resolve source entry (file or dir) and forbid destination if it exists (file or dir)
			TreeEntry srcEntry = findEntry(store, root, oldPath, branch);
			if( srcEntry == null ) throw new IllegalArgumentException("Source does not exist");
			if( findEntry(store, root, newPath, branch) != null ) throw new IllegalArgumentException("Destination already exists");

			// If source is a directory, forbid moving inside itself
			if( srcEntry.isDir() )
			{
				if( newPath.startsWith(oldPath.endsWith("/") ? oldPath : oldPath + "/") )
					throw new IllegalArgumentException("Destination is inside the source folder");
			}

			// Split paths
			LinkedList<String> oldParts = new LinkedList<>();
			for( String s : StringUtils.split(oldPath, "/") ) oldParts.add(s);
			LinkedList<String> newParts = new LinkedList<>();
			for( String s : StringUtils.split(newPath, "/") ) newParts.add(s);
			if( oldParts.isEmpty() || newParts.isEmpty() ) throw new IllegalArgumentException("Invalid paths");

			// Longest common prefix
			int longestCommonPrefix = 0;
			while( longestCommonPrefix < oldParts.size() && longestCommonPrefix < newParts.size()
					&& Objects.equals(oldParts.get(longestCommonPrefix), newParts.get(longestCommonPrefix)) )
				longestCommonPrefix++;

			// Build COMMON spine from root to LCA (inclusive). Tuple<nameOrNullAtRoot, entries>
			LinkedList<Tuple<String, List<TreeEntry>>> common = new LinkedList<>();
			common.add(Tuple.of(null, decodeTree(object(store, root, latestTreeSha).b))); // root entries

			String walkSha = latestTreeSha;
			for( int d = 0; d < longestCommonPrefix; d++ )
			{
				String name = oldParts.get(d);
				int dirIdx = findDirIndex(common.getLast().b, name);
				TreeEntry dir = dirIdx >= 0 ? common.getLast().b.get(dirIdx) : null;
				if( dir == null ) throw new IllegalStateException("Broken common spine at: " + name);
				walkSha = dir.sha;
				common.add(Tuple.of(name, decodeTree(object(store, root, walkSha).b)));
			}
			// LCA entries = common.getLast().b

			// REMOVE chain: from old parent down to just above the leaf (so we mutate the parent first)
			LinkedList<Tuple<String, List<TreeEntry>>> removeChain = new LinkedList<>();
			if( oldParts.size() - 2 >= longestCommonPrefix )
			{
				List<TreeEntry> curEntries = common.getLast().b;
				for( int d = longestCommonPrefix; d <= oldParts.size() - 2; d++ )
				{
					String seg = oldParts.get(d);
					int dirIdx = findDirIndex(curEntries, seg);
					TreeEntry dir = dirIdx >= 0 ? curEntries.get(dirIdx) : null;
					if( dir == null ) throw new IllegalStateException("Old branch missing at: " + seg);
					List<TreeEntry> childEntries = decodeTree(object(store, root, dir.sha).b);
					removeChain.add(Tuple.of(seg, childEntries));
					curEntries = childEntries;
				}
			}

			// ADD chain: from LCA down to new parent (create missing parents)
			LinkedList<Tuple<String, List<TreeEntry>>> add = new LinkedList<>();
			{
				List<TreeEntry> curEntries = common.getLast().b;
				for( int d = longestCommonPrefix; d <= newParts.size() - 2; d++ )
				{
					String seg = newParts.get(d);
					int dirIdx = findDirIndex(curEntries, seg);
					TreeEntry dir = dirIdx >= 0 ? curEntries.get(dirIdx) : null;
					if( dir == null )
					{
						// create placeholder child entries (empty dir)
						List<TreeEntry> emptyEntries = new ArrayList<>();
						add.add(Tuple.of(seg, emptyEntries));
						// synthetic reference so we can continue walking
						curEntries.add(new TreeEntry("40000", seg, "<pending>"));
					}
					else
					{
						List<TreeEntry> childEntries = decodeTree(object(store, root, dir.sha).b);
						add.add(Tuple.of(seg, childEntries));
						curEntries = childEntries;
					}
				}
			}

			String oldLeafName = oldParts.getLast();
			String newLeafName = newParts.getLast();
			final String movedMode = srcEntry.mode;  // keep original mode (file/dir/symlink/submodule)
			final String movedSha  = srcEntry.sha;   // reattach same object/subtree

			// Fast path: same parent, just rename entry
			boolean sameParent = (oldParts.size() == newParts.size() && longestCommonPrefix == oldParts.size() - 1);
			if( sameParent )
			{
				List<TreeEntry> parentEntries = common.getLast().b;

				// collision at destination name (any type)
				if( findEntryIndex(parentEntries, newLeafName) >= 0 )
					throw new IllegalArgumentException("Destination already exists");

				// remove old entry (any type)
				int oldPos = findEntryIndex(parentEntries, oldLeafName);
				if( oldPos < 0 ) throw new IllegalStateException("Old entry vanished in parent");
				parentEntries.remove(oldPos);

				// insert same SHA with new name and original mode
				parentEntries.add(new TreeEntry(movedMode, newLeafName, movedSha));
				String parentSha = writeTree(store, root, parentEntries);

				// bubble up through COMMON to root
				String childSha = parentSha;
				for( int up = common.size() - 2; up >= 0; up-- )
				{
					List<TreeEntry> entries = new ArrayList<>(common.get(up).b);
					String childName = common.get(up + 1).a;
					if( childName != null )
					{
						int upIdx = findDirIndex(entries, childName);
						if( upIdx >= 0 )
							entries.set(upIdx, new TreeEntry(entries.get(upIdx).mode, entries.get(upIdx).name, childSha));
					}
					childSha = writeTree(store, root, entries);
				}
				String newRootTreeSha = childSha;
				commit(store, root, newRootTreeSha, author, message, branch);
				return;
			}

			// General case: different parents
			String updatedOldBranchChildSha; // may become null if pruning removes the first differing child under LCA
			{
				// parent entries of the leaf are either last of removeChain, else LCA
				List<TreeEntry> curEntries = removeChain.isEmpty() ? common.getLast().b : removeChain.getLast().b;

				// Ensure the leaf exists in that parent (any type)
				int pos = findEntryIndex(curEntries, oldLeafName);
				if( pos < 0 ) throw new IllegalStateException("Old entry not found in its parent");
				curEntries.remove(pos);

				boolean pruneBranch = curEntries.isEmpty();
				String childSha = null;
				if( !pruneBranch )
				{
					childSha = writeTree(store, root, curEntries);
				}

				// bubble towards LCA across removeChain (parent-of-leaf up to just below LCA), pruning empty dirs
				for( int i = removeChain.size() - 1; i >= 0; i-- )
				{
					String dirName = removeChain.get(i).a;
					List<TreeEntry> parentEntries =
							(i == 0) ? new ArrayList<>(common.getLast().b) : new ArrayList<>(removeChain.get(i - 1).b);

					int idx = findDirIndex(parentEntries, dirName);

					if( idx < 0 ) throw new IllegalStateException("Ancestor dir missing on old branch: " + dirName);

					if( pruneBranch )
					{
						parentEntries.remove(idx);
						if( parentEntries.isEmpty() )
						{
							pruneBranch = true;
							childSha = null;
						}
						else
						{
							childSha = writeTree(store, root, parentEntries);
							pruneBranch = false;
						}
					}
					else
					{
						TreeEntry e = parentEntries.get(idx);
						parentEntries.set(idx, new TreeEntry(e.mode, e.name, childSha));
						childSha = writeTree(store, root, parentEntries);
					}
				}
				updatedOldBranchChildSha = childSha; // null if the first differing child under LCA got pruned away
			}

			// Destination branch: create missing parents, insert the entry with original mode+sha, bubble up to LCA
			String updatedNewBranchChildSha;
			{
				List<TreeEntry> parentEntriesAtLevel = common.getLast().b;

				// descend via ADD chain creating dirs as needed (already represented in 'add')
				for( int i = 0; i <= add.size() - 1; i++ )
				{
					String seg = add.get(i).a;
					List<TreeEntry> entriesHere = add.get(i).b;

					int existingIdx = findDirIndex(parentEntriesAtLevel, seg);
					if( existingIdx < 0 )
					{
						// write empty child dir
						String newDirSha = writeTree(store, root, entriesHere);
						parentEntriesAtLevel.add(new TreeEntry("40000", seg, newDirSha));
						// write parent to stabilize state (helps when bubbling later)
						writeTree(store, root, parentEntriesAtLevel);
						// descend into the new empty dir
						parentEntriesAtLevel = entriesHere;
					}
					else
					{
						parentEntriesAtLevel = entriesHere;
					}
				}

				// At new parent: forbid collision on newLeafName (file or dir)
				if( findEntryIndex(parentEntriesAtLevel, newLeafName) >= 0 )
					throw new IllegalArgumentException("Destination already exists");

				// Insert with original mode + sha (works for blob/dir/symlink/submodule)
				parentEntriesAtLevel.add(new TreeEntry(movedMode, newLeafName, movedSha));
				String childSha = writeTree(store, root, parentEntriesAtLevel);

				// Bubble up along ADD chain to LCA
				for( int i = add.size() - 1; i >= 0; i-- )
				{
					String dirName = add.get(i).a;
					List<TreeEntry> parentEntries =
							(i == 0) ? new ArrayList<>(common.getLast().b) : new ArrayList<>(add.get(i - 1).b);

					int idx = findDirIndex(parentEntries, dirName);

					if( idx < 0 )
						parentEntries.add(new TreeEntry("40000", dirName, childSha));
					else
					{
						TreeEntry e = parentEntries.get(idx);
						parentEntries.set(idx, new TreeEntry(e.mode, e.name, childSha));
					}
					childSha = writeTree(store, root, parentEntries);
				}
				updatedNewBranchChildSha = childSha;
			}

			// Stitch at LCA and bubble to root
			{
				List<TreeEntry> lcaEntries = new ArrayList<>(common.getLast().b);

				// Old child under LCA: replace with updatedOldBranchChildSha, or remove if pruned entirely
				if( longestCommonPrefix < oldParts.size() )
				{
					String oldChildName = oldParts.get(longestCommonPrefix);
					int idx = findDirIndex(lcaEntries, oldChildName);
					if( idx >= 0 )
					{
						if( updatedOldBranchChildSha == null ) lcaEntries.remove(idx);
						else {
							TreeEntry e = lcaEntries.get(idx);
							lcaEntries.set(idx, new TreeEntry(e.mode, e.name, updatedOldBranchChildSha));
						}
					}
				}

				// New child under LCA: add or replace with updatedNewBranchChildSha
				if( longestCommonPrefix < newParts.size() )
				{
					String newChildName = newParts.get(longestCommonPrefix);
					int idx = findDirIndex(lcaEntries, newChildName);
					if( idx < 0 )
						lcaEntries.add(new TreeEntry("40000", newChildName, updatedNewBranchChildSha));
					else {
						TreeEntry e = lcaEntries.get(idx);
						lcaEntries.set(idx, new TreeEntry(e.mode, e.name, updatedNewBranchChildSha));
					}
				}

				String childSha = writeTree(store, root, lcaEntries);

				for( int up = common.size() - 2; up >= 0; up-- )
				{
					List<TreeEntry> entries = new ArrayList<>(common.get(up).b);
					String childName = common.get(up + 1).a;
					if( childName != null )
					{
						int upIdx = findDirIndex(entries, childName);
						if( upIdx >= 0 )
							entries.set(upIdx, new TreeEntry(entries.get(upIdx).mode, entries.get(upIdx).name, childSha));
					}
					childSha = writeTree(store, root, entries);
				}
				String newRootTreeSha = childSha;
				commit(store, root, newRootTreeSha, author, message, branch);
			}
		}
	}

	/**
	 * Returns the list of affected files between two commits.
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param base the reference commit to walk back to (excluded)
	 * @param head the reference commit to consider changes from (included)
	 * @return the list of affected files (path, previous content, new content). If the same file was modified multiple times, only the content closest to the head is considered. If a file was deleted, the content is null.
	 */
	public static Set<Triple<String, byte[], byte[]>> affectedFiles(Storage.Type store, String root, String base, String head)
	{
		String newTree = commitTree(store, root, head);
		String oldTree = commitTree(store, root, base);
		return diffTrees(store, root, newTree, oldTree, "");
	}

	/**
	 * Returns the sha of the tree object referenced in a commit
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param commit the sha of the commit
	 * @return the sha of the tree object
	 */
	static String commitTree(Storage.Type store, String root, String commit)
	{
		Tuple<String, byte[]> c = object(store, root, commit);
		if( c == null || !"commit".equals(c.a) )
			throw new IllegalArgumentException("Invalid commit: " + commit);
		String tree = parseCommitHeaders(c.b).a;
		if( tree == null ) throw new IllegalStateException("No tree in commit: " + commit);
		return tree;
	}

	/**
	 * Returns the difference between two trees
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param newTreeSha the most recent tree
	 * @param oldTreeSha the oldest tree
	 * @param prefix the path prefix of the current directory
	 * @return the list of modified files (path, previous content, new content). If a file was deleted, the new content is null.
	 */
	static Set<Triple<String, byte[], byte[]>> diffTrees(Storage.Type store, String root, String newTreeSha, String oldTreeSha, String prefix)
	{
		if( prefix == null || prefix.isBlank() ) prefix = "";

		Map<String, String> newEntries = listRecursive(store, root, newTreeSha, prefix);
		Map<String, String> oldEntries = (oldTreeSha == null ? Collections.emptyMap() : listRecursive(store, root, oldTreeSha, prefix));

		Set<Triple<String, byte[], byte[]>> diff = new HashSet<>();
		Set<String> allPaths = new HashSet<>();
		allPaths.addAll(newEntries.keySet());
		allPaths.addAll(oldEntries.keySet());

		for( String path : allPaths )
		{
			if( path.endsWith("/") ) continue;

			String newSha = newEntries.get(path);
			String oldSha = oldEntries.get(path);

			if( Objects.equals(newSha, oldSha) ) continue;

			byte[] oldBlob = (oldSha == null ? null : content(store, root, "blob", oldSha));
			if( newSha != null )
			{
				// new or modified file
				byte[] newBlob = content(store, root, "blob", newSha);
				diff.add(Triple.of(path, oldBlob, newBlob));
			}
			else
			{
				// deleted file
				diff.add(Triple.of(path, oldBlob, null));
			}
		}

		return diff;
	}

	/**
	 * Returns the history of all modifications of the specified file path
	 *
	 * @param store the storage backend
	 * @param root the root path of the bare Git repository
	 * @param ref the branch or tag to start from
	 * @param path the path to inspect
	 * @return the list of commits (author, date, message, sha)
	 */
	public static List<Quadruple<String, Long, String, String>> history(Storage.Type store, String root, String ref, String path) throws Exception
	{
		if( path == null || path.isBlank() || path.endsWith("/") )
			throw new IllegalArgumentException("Invalid path: " + path);

		Set<String> seenCommits = new HashSet<>();
		List<Quadruple<String, Long, String, String>> result = new ArrayList<>();
		Deque<String> stack = new ArrayDeque<>();

		String sha = latestCommit(store, root, ref);
		if( sha == null ) throw new IllegalArgumentException("Invalid ref: " + ref);

		stack.push(sha);

		while( !stack.isEmpty() )
		{
			sha = stack.pop();
			if( !seenCommits.add(sha) ) continue;

			// fetch the commit object
			Tuple<String, byte[]> commitObj = object(store, root, sha);
			if( commitObj == null || !"commit".equals(commitObj.a) ) continue;

			// parse the referenced 'tree' and 'parent' commits
			Tuple<String, List<String>> headers = parseCommitHeaders(commitObj.b);
			String tree = headers.a;
			List<String> parents = headers.b;

			// if no parent, it's the root commit
			String parentTree = null;
			if( !parents.isEmpty() )
			{
				// get the parent tree to compare against
				Tuple<String, byte[]> parentCommit = object(store, root, parents.get(0));
				if( parentCommit == null || !"commit".equals(parentCommit.a) ) throw new IllegalStateException("Invalid parent commit. Repository likely corrupted.");
				parentTree = parseCommitHeaders(parentCommit.b).a;
			}

			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			for( Triple<String, byte[], byte[]> affected : diffTrees(store, root, tree, parentTree, "") )
			{
				if( !affected.a.equals(path) ) continue;

				Quadruple<String, Long, String, String> entry = Quadruple.of(null, null, null, null);

				StringBuilder msgBuilder = new StringBuilder();
				boolean inMessage = false;
				String[] lines = new String(commitObj.b, StandardCharsets.UTF_8).split("\n");
				for( String line : lines )
				{
					if( inMessage ) msgBuilder.append(line).append("\n");
					else if( line.startsWith("author") )
					{
						// example: author John Doe <john@example.com> 1722783600 +0200
						String[] tokens = line.substring(7).trim().split(" ");
						if( tokens.length >= 3 )
						{
							entry.a = String.join(" ", Arrays.copyOf(tokens, tokens.length - 2));
							entry.b = Long.parseLong(tokens[tokens.length - 2]) * 1000;
						}
					}
					else if( line.isBlank() ) inMessage = true;
				}
				entry.c = msgBuilder.toString().trim();

				if( affected.c == null ) // file deleted
					entry.d = "";
				else
				{
					sha1.update(("blob " + affected.c.length + "\0").getBytes(StandardCharsets.UTF_8));
					entry.d = sha2hex(sha1.digest(affected.c));
				}

				result.add(entry);
			}

			stack.addAll(parents);
		}

		return result;
	}
}
