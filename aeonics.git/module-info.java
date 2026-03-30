module aeonics.git
{
	requires aeonics.boot;
	requires transitive aeonics.core;
	requires transitive aeonics.http;
	exports aeonics.git;
	provides aeonics.Plugin with local.Main;
}
