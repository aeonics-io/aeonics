## Plugin: "aeonics.git"

This Aeonics software plugin provides GIT server capabilities and defines
high level endpoints for HTTP transport.

## Compile and package

You can use your favourite tool (Maven, Gradle,...) but to be honest, we prefer
the plain simple standard and out-of-the-box `javac`.

The binary distribution of the *aeonics.boot* jar should be in the
current directory, and the *aeonics.core*, *aeonics.http* jars should be in the `plugins` 
directory.

```shell
javac -source 11 -target 11 -nowarn -XDignore.symbol.file \
      -d aeonics.git/bin \
      --module-path .;./plugins \
      --module-source-path .\
      --module aeonics.git

jar -c --file=aeonics.git.jar \
    -C aeonics.git/bin/aeonics.git \
    .
```

## Deployment

Place the binary distribution in the `plugins` folder of your installation.
