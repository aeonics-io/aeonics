package aeonics.git;

public class Git
{
	private Git() {}

	/**
	 * Parses the branch name from a HEAD symbolic ref string.
	 * Handles multi-segment branch names like {@code feature/foo}.
	 * @param head the raw HEAD content, e.g. {@code "ref: refs/heads/main\n"}
	 * @return the branch name, e.g. {@code "main"} or {@code "feature/foo"}
	 */
	public static String headBranch(String head)
	{
		// "ref: refs/heads/<branch>\n" — skip past "refs/heads/" (16 chars after "ref: ")
		return head.substring(16).trim();
	}
}
