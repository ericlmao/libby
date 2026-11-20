/**
 * A lightweight, built-in Maven POM resolver used to download a library's
 * transitive dependencies. See
 * {@link gg.moonrise.libby.resolver.DependencyResolver} for the supported
 * POM features. Apart from {@link gg.moonrise.libby.resolver.TransitiveDependencyException},
 * types in this package are public only so the library manager can use them
 * across packages; they are not a stable API.
 */
@NullMarked
package gg.moonrise.libby.resolver;

import org.jspecify.annotations.NullMarked;
