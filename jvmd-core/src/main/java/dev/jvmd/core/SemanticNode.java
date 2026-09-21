package dev.jvmd.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Detached ownership node. References are payload IDs, never recursive child links. */
public final class SemanticNode {
  public record Identity(String domain, String schema, String algorithm, String digest) {
    public String encoded() {
      return schema + ":" + algorithm + ":" + domain + ":" + digest;
    }
  }

  private static final String SCHEMA = "semantic-v1";
  private static final String ALGORITHM = "merkle-sha256";
  private final String domain;
  private final List<String> fields;
  private final String payload;
  private final PersistentMap<String, SemanticNode> children;
  private final Identity identity;

  private SemanticNode(
      String domain,
      List<String> fields,
      String payload,
      PersistentMap<String, SemanticNode> children) {
    this.domain = domain;
    this.fields = fields;
    this.payload = payload;
    this.children = children;
    this.identity =
        new Identity(
            domain,
            SCHEMA,
            ALGORITHM,
            digest("node", List.of(domain, payload, children.summary())));
  }

  public static SemanticNode leaf(String domain, List<String> fields, SemanticNode prior) {
    fields = List.copyOf(fields);
    if (prior != null && !prior.domain.equals(domain)) prior = null;
    if (prior != null && prior.domain.equals(domain) && prior.fields.equals(fields)) return prior;
    var children =
        prior == null
            ? PersistentMap.<String, SemanticNode>empty(
                (key, value, left, right) ->
                    digest("keyed-set", List.of(key, value.identity.encoded(), left, right)))
            : prior.children;
    return new SemanticNode(domain, fields, digest("payload", fields), children);
  }

  public static SemanticNode empty(String domain) {
    return leaf(domain, List.of(), null);
  }

  public Identity identity() {
    return identity;
  }

  public Map<String, SemanticNode> children() {
    return children;
  }

  /**
   * Unique-key unordered replacement boundary. A future accumulator has both old and new
   * contributions here; semantic consumers never depend on the internal Merkle tree shape. Ordered
   * inputs belong in the length-delimited payload, not this collection.
   */
  public SemanticNode withChild(String key, SemanticNode value) {
    var old = children.get(key);
    if (old == value || old != null && value != null && old.identity.equals(value.identity))
      return this;
    var next = value == null ? children.without(key) : children.with(key, value);
    return next == children ? this : new SemanticNode(domain, fields, payload, next);
  }

  /** UTF-8 length-delimited sequences; field boundaries and order are significant. */
  private static String digest(String domain, List<String> fields) {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      append(hash, SCHEMA);
      append(hash, ALGORITHM);
      append(hash, domain);
      for (String field : fields) append(hash, field);
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void append(MessageDigest hash, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    int length = bytes.length;
    hash.update((byte) (length >>> 24));
    hash.update((byte) (length >>> 16));
    hash.update((byte) (length >>> 8));
    hash.update((byte) length);
    hash.update(bytes);
  }
}
