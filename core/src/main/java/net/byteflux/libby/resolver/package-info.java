/**
 * A lightweight, built-in Maven POM resolver used to download a library's
 * transitive dependencies. See
 * {@link net.byteflux.libby.resolver.DependencyResolver} for the supported
 * POM features. Apart from {@link net.byteflux.libby.resolver.TransitiveDependencyException},
 * types in this package are public only so the library manager can use them
 * across packages; they are not a stable API.
 */
@NullMarked
package net.byteflux.libby.resolver;

import org.jspecify.annotations.NullMarked;
