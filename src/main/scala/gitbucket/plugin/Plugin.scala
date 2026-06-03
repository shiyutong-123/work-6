package gitbucket.plugin

import gitbucket.core.plugin.Plugin as CorePlugin

/**
 * Alias for gitbucket.core.plugin.Plugin to support Java ServiceLoader.
 * This is the trait that plugins should extend to be recognized by ServiceLoader.
 */
abstract class Plugin extends CorePlugin
