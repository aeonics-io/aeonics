package local;

import aeonics.Plugin;
import aeonics.manager.Config;
import aeonics.manager.Lifecycle;
import aeonics.manager.Lifecycle.Phase;
import aeonics.manager.Manager;
import aeonics.template.Factory;
import aeonics.template.Parameter;
import aeonics.git.Git;
import aeonics.git.GitRepo;
import aeonics.git.GitEndpoints;

public class Main extends Plugin
{
	public String summary() { return "Aeonics Git v0.1"; }
	public String description() { return "Git bare repository server"; }

	public void start()
	{
		Lifecycle.on(Phase.LOAD, this::onLoad);
		Lifecycle.on(Phase.RUN, this::onRun);
	}

	private void onLoad()
	{
		Factory.add(new GitRepo());

		Config config = Manager.of(Config.class);
		config.declare(Git.class, new Parameter("root")
			.summary("Git URL root")
			.description("The URL root prefix for git endpoints.")
			.format(Parameter.Format.TEXT)
			.defaultValue("/git"));
		config.declare(Git.class, new Parameter("scope")
			.summary("Token scope")
			.description("The required token scope for git operations.")
			.format(Parameter.Format.TEXT)
			.defaultValue("git"));
	}

	private void onRun()
	{
		GitEndpoints.register();
	}
}
