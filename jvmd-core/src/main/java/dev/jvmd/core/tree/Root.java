package dev.jvmd.core.tree;

import dev.jvmd.core.hash.Identity;

/**
 * The identity of a whole tree (stage 1, B.3). A tree with one level-0 node has level 0 and the hash of that node: there is no
 * wrapper node.
 */
public record Root(Identity hash, Identity sum, int count, int level) { }
