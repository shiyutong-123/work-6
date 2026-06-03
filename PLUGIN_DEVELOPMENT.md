# GitBucket Plugin Development Guide (ServiceLoader Version)

## Overview

This guide explains how to develop GitBucket plugins using the new Java ServiceLoader-based plugin system.

## 1. Create a Plugin Project

### 1.1 Project Structure

```
your-plugin/
├── src/
│   └── main/
│       ├── scala/
│       │   └── your/
│       │       └── package/
│       │           └── YourPlugin.scala
│       └── resources/
│           └── META-INF/
│               └── services/
│                   └── gitbucket.plugin.Plugin
└── build.sbt
```

### 1.2 Plugin Class

Create a plugin class that extends `gitbucket.plugin.Plugin`:

```scala
package your.package

import gitbucket.plugin.Plugin
import gitbucket.core.controller.Context
import gitbucket.core.model.Account
import gitbucket.core.plugin.{Link, PluginRegistry}
import gitbucket.core.service.RepositoryService.RepositoryInfo
import gitbucket.core.service.SystemSettingsService.SystemSettings
import io.github.gitbucket.solidbase.model.Version
import javax.servlet.ServletContext

class YourPlugin extends Plugin {

  override val pluginId: String = "your-plugin-id"
  override val pluginName: String = "Your Plugin Name"
  override val description: String = "Description of your plugin"
  override val versions: Seq[Version] = Seq(
    new Version("1.0.0")
  )

  override def initialize(registry: PluginRegistry, context: ServletContext, settings: SystemSettings): Unit = {
    super.initialize(registry, context, settings)
    // Your initialization logic here
  }

  override def shutdown(registry: PluginRegistry, context: ServletContext, settings: SystemSettings): Unit = {
    // Your shutdown logic here
  }
}
```

### 1.3 Service Provider Configuration

Create the service provider file `src/main/resources/META-INF/services/gitbucket.plugin.Plugin`:

```
your.package.YourPlugin
```

### 1.4 build.sbt Example

```scala
name := "your-plugin"
organization := "your.organization"
version := "1.0.0"
scalaVersion := "2.13.18"

libraryDependencies ++= Seq(
  "io.github.gitbucket" %% "gitbucket" % "4.46.1" % Provided
)
```

## 2. Packaging and Deployment

### 2.1 Package Your Plugin

```bash
sbt package
```

This will create a JAR file in `target/scala-2.13/`.

### 2.2 Deploy Your Plugin

Copy the JAR file to GitBucket's plugins directory:
- Default: `~/.gitbucket/plugins/`
- Or use a custom directory configured by `gitbucket.pluginDir` system property

## 3. Hot Reloading

GitBucket now supports hot reloading of plugins without restarting the JVM. The plugin system:

1. Caches loaded plugins using file modification time
2. Automatically detects plugin changes via file watcher
3. Reloads plugins when changes are detected

### 3.1 Update an Existing Plugin

Simply replace the plugin JAR file in the plugins directory, and GitBucket will:
- Detect the file change
- Clear the cache for the updated plugin
- Reload and reinitialize the plugin

### 3.2 Uninstall a Plugin

To uninstall a plugin:
1. Remove the plugin JAR from the plugins directory
2. GitBucket will automatically detect the change and unload the plugin
3. Or use the uninstall API: `PluginRegistry.uninstall(pluginId, ...)`

## 4. Compatibility

The new plugin system maintains backward compatibility:
- Plugins that extend `gitbucket.core.plugin.Plugin` will still work
- The new `gitbucket.plugin.Plugin` is simply an alias for the core trait
- ServiceLoader provides an additional, more standard way to discover and load plugins
