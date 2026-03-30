package aeonics.git;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import aeonics.entity.Entity;
import aeonics.entity.Storage;
import aeonics.template.Item;
import aeonics.template.Parameter;
import aeonics.template.Relationship;
import aeonics.template.Template;
import aeonics.util.StringUtils;
import aeonics.util.Tuples.Triple;
import aeonics.util.Tuples.Tuple;

public class GitRepo extends Item<GitRepo.Type>
{
	public static class Type extends Entity
	{
		@Override
		public final String category() { return StringUtils.toLowerCase(GitRepo.class); }

		/**
		 * Returns the storage backend for this repository.
		 * @return the storage entity, or null if not configured
		 */
		public Storage.Type store()
		{
			Entity s = firstRelation("storage");
			return s == null ? null : s.<Storage.Type>cast();
		}

		/**
		 * Returns the root path in the storage.
		 * @return the root path
		 */
		public String root() { return valueOf("root").asString(); }

		/**
		 * Returns the default branch name.
		 * @return the branch name, defaults to "main"
		 */
		public String branch()
		{
			String b = valueOf("branch").asString();
			return (b == null || b.isEmpty()) ? "main" : b;
		}

		/**
		 * Hook called after a push operation completes successfully.
		 * Override this method in a subclass to add custom behavior (e.g. compile, deploy).
		 *
		 * @param refs the refs that were updated (ref name -> Tuple(oldSha, newSha))
		 * @param affectedFiles the set of affected files (path, previous content, new content)
		 * @return a status message to send back to the git client
		 */
		public String onPush(Map<String, Tuple<String, String>> refs, Set<Triple<String, byte[], byte[]>> affectedFiles) { return ""; }
	}

	protected Class<? extends GitRepo.Type> defaultTarget() { return GitRepo.Type.class; }
	protected Supplier<? extends GitRepo.Type> defaultCreator() { return GitRepo.Type::new; }
	protected Class<? extends GitRepo> category() { return GitRepo.class; }

	@Override
	public Template<? extends GitRepo.Type> template()
	{
		return super.template()
			.summary("Git Repository")
			.description("A bare Git repository backed by a Storage entity.")
			.add(new Parameter("root")
				.summary("Root path")
				.description("The root path in the storage.")
				.format(Parameter.Format.TEXT)
				.optional(false))
			.add(new Parameter("branch")
				.summary("Default branch")
				.description("The default branch name.")
				.format(Parameter.Format.TEXT)
				.optional(true)
				.defaultValue("main"))
			.add(new Relationship("storage")
				.category(Storage.class)
				.summary("Storage")
				.description("The storage backend for this repository.")
				.max(1));
	}
}
