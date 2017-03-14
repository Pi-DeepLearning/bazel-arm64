// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.vfs;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.util.StringCanonicalizer;
import com.google.devtools.build.lib.vfs.FileSystem.HashFunction;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * <p>Instances of this class represent pathnames, forming a tree
 * structure to implement sharing of common prefixes (parent directory names).
 * A node in these trees is something like foo, bar, .., ., or /. If the
 * instance is not a root path, it will have a parent path. A path can also
 * have children, which are indexed by name in a map.
 *
 * <p>There is some limited support for Windows-style paths. Most importantly, drive identifiers
 * in front of a path (c:/abc) are supported. However, Windows-style backslash separators
 * (C:\\foo\\bar) and drive-relative paths ("C:foo") are explicitly not supported, same with
 * advanced features like \\\\network\\paths and \\\\?\\unc\\paths.
 *
 * <p>{@link FileSystem} implementations maintain pointers into this graph.
 */
@ThreadSafe
public class Path implements Comparable<Path>, Serializable {

  /** Filesystem-specific factory for {@link Path} objects. */
  public static interface PathFactory {

    /**
     * A translated path, i.e. where the parent and name are potentially different from the input.
     *
     * <p>See {@link PathFactory#translatePath} for details.
     */
    public static final class TranslatedPath {
      public final Path parent;
      public final String child;
      public final boolean cacheable;

      public TranslatedPath(Path parent, String child, boolean cacheable) {
        this.parent = parent;
        this.child = child;
        this.cacheable = cacheable;
      }

      public TranslatedPath(Path parent, String child) {
        this(parent, child, true);
      }
    }

    /**
     * Creates the root of all paths used by a filesystem.
     *
     * <p>All other paths are instantiated via {@link Path#createChildPath(String)} which calls
     * {@link #createChildPath(Path, String)}.
     *
     * <p>Beware: this is called during the FileSystem constructor which may occur before subclasses
     * are completely initialized.
     */
    Path createRootPath(FileSystem filesystem);

    /**
     * Create a child path of the given parent.
     *
     * <p>All {@link Path} objects are instantiated via this method, with the sole exception of the
     * filesystem root, which is created by {@link #createRootPath(FileSystem)}.
     */
    Path createChildPath(Path parent, String childName);

    /**
     * Translate the input path in a filesystem-specific way if necessary.
     *
     * <p>On Unix filesystems this operation is typically idempotent, but on Windows this can be
     * used to translate absolute Unix paths to absolute Windows paths, e.g. "/c" to "C:/" or "/usr"
     * to "C:/tools/msys64/usr".
     */
    TranslatedPath translatePath(Path parent, String child);
  }

  private static FileSystem fileSystemForSerialization;

  /**
   * We need to specify used FileSystem. In this case we can save memory during the serialization.
   */
  public static void setFileSystemForSerialization(FileSystem fileSystem) {
    fileSystemForSerialization = fileSystem;
  }

  /**
   * Returns FileSystem that we are using.
   */
  public static FileSystem getFileSystemForSerialization() {
    return fileSystemForSerialization;
  }

  // These are basically final, but can't be marked as such in order to support serialization.
  private FileSystem fileSystem;
  private String name;
  private Path parent;
  private int depth;
  private int hashCode;

  private static final ReferenceQueue<Path> REFERENCE_QUEUE = new ReferenceQueue<>();

  private static class PathWeakReferenceForCleanup extends WeakReference<Path> {
    final Path parent;
    final String baseName;

    PathWeakReferenceForCleanup(Path referent, ReferenceQueue<Path> referenceQueue) {
      super(referent, referenceQueue);
      parent = referent.getParentDirectory();
      baseName = referent.getBaseName();
    }
  }

  private static final Thread PATH_CHILD_CACHE_CLEANUP_THREAD = new Thread("Path cache cleanup") {
    @Override
    public void run() {
      while (true) {
        try {
          PathWeakReferenceForCleanup ref = (PathWeakReferenceForCleanup) REFERENCE_QUEUE.remove();
          Path parent = ref.parent;
          synchronized (parent) {
            // It's possible that since this reference was enqueued for deletion, the Path was
            // recreated with a new entry in the map. We definitely shouldn't delete that entry, so
            // check to make sure they're the same.
            Reference<Path> currentRef = ref.parent.children.get(ref.baseName);
            if (currentRef == ref) {
              ref.parent.children.remove(ref.baseName);
            }
          }
        } catch (InterruptedException e) {
          // Ignored.
        }
      }
    }
  };

  static {
    PATH_CHILD_CACHE_CLEANUP_THREAD.setDaemon(true);
    PATH_CHILD_CACHE_CLEANUP_THREAD.start();
  }

  /**
   * A mapping from a child file name to the {@link Path} representing it.
   *
   * <p>File names must be a single path segment.  The strings must be
   * canonical.  We use IdentityHashMap instead of HashMap for reasons of space
   * efficiency: instances are smaller by a single word.  Also, since all path
   * segments are interned, the universe of Paths holds a minimal number of
   * references to strings.  (It's doubtful that there's any time gain from use
   * of an IdentityHashMap, since the time saved by avoiding string equality
   * tests during hash lookups is probably equal to the time spent eagerly
   * interning strings, unless the collision rate is high.)
   *
   * <p>The Paths are stored as weak references to ensure that a live
   * Path for a directory does not hold a strong reference to all of its
   * descendants, which would prevent collection of paths we never intend to
   * use again.  Stale references in the map must be treated as absent.
   *
   * <p>A Path may be recycled once there is no Path that refers to it or
   * to one of its descendants.  This means that any data stored in the
   * Path instance by one of its subclasses must be recoverable: it's ok to
   * store data in Paths as an optimization, but there must be another
   * source for that data in case the Path is recycled.
   *
   * <p>We intentionally avoid using the existing library classes for reasons of
   * space efficiency: while ConcurrentHashMap would reduce our locking
   * overhead, and ReferenceMap would simplify the code a little, both of those
   * classes have much higher per-instance overheads than IdentityHashMap.
   *
   * <p>The Path object must be synchronized while children is being
   * accessed.
   */
  private volatile IdentityHashMap<String, Reference<Path>> children;

  /**
   * Create a path instance.
   *
   * <p>Should only be called by {@link PathFactory#createChildPath(Path, String)}.
   *
   * @param name the name of this path; it must be canonicalized with {@link
   *     StringCanonicalizer#intern}
   * @param parent this path's parent
   */
  protected Path(FileSystem fileSystem, String name, Path parent) {
    this.fileSystem = fileSystem;
    this.name = name;
    this.parent = parent;
    this.depth = parent == null ? 0 : parent.depth + 1;

    // No need to include the drive letter in the hash code, because it's derived from the parent
    // and/or the name.
    if (fileSystem == null || fileSystem.isFilePathCaseSensitive()) {
      this.hashCode = Objects.hash(parent, name);
    } else {
      this.hashCode = Objects.hash(parent, name.toLowerCase());
    }
  }

  /**
   * Create the root path.
   *
   * <p>Should only be called by {@link PathFactory#createRootPath(FileSystem)}.
   */
  protected Path(FileSystem fileSystem) {
    this(fileSystem, StringCanonicalizer.intern("/"), null);
  }

  private void writeObject(ObjectOutputStream out) throws IOException {
    Preconditions.checkState(fileSystem == fileSystemForSerialization, fileSystem);
    out.writeUTF(getPathString());
  }

  private void readObject(ObjectInputStream in) throws IOException {
    fileSystem = fileSystemForSerialization;
    String p = in.readUTF();
    PathFragment pf = new PathFragment(p);
    PathFragment parentDir = pf.getParentDirectory();
    if (parentDir == null) {
      this.name = "/";
      this.parent = null;
      this.depth = 0;
    } else {
      this.name = pf.getBaseName();
      this.parent = fileSystem.getPath(parentDir);
      this.depth = this.parent.depth + 1;
    }
    this.hashCode = Objects.hash(parent, name);
    reinitializeAfterDeserialization();
  }

  /**
   * Returns the filesystem instance to which this path belongs.
   */
  public FileSystem getFileSystem() {
    return fileSystem;
  }

  public boolean isRootDirectory() {
    return parent == null;
  }

  protected Path createChildPath(String childName) {
    return fileSystem.getPathFactory().createChildPath(this, childName);
  }

  /**
   * Reinitializes this object after deserialization.
   *
   * <p>Derived classes should use this hook to initialize additional state.
   */
  protected void reinitializeAfterDeserialization() {}

  /**
   * Returns true if {@code ancestorPath} may be an ancestor of {@code path}.
   *
   * <p>The return value may be a false positive, but it cannot be a false negative. This means that
   * a true return value doesn't mean the ancestor candidate is really an ancestor, however a false
   * return value means it's guaranteed that {@code ancestorCandidate} is not an ancestor of this
   * path.
   *
   * <p>Subclasses may override this method with filesystem-specific logic, e.g. a Windows
   * filesystem may return false if the ancestor path is on a different drive than this one, because
   * it is then guaranteed that the ancestor candidate cannot be an ancestor of this path.
   *
   * @param ancestorCandidate the path that may or may not be an ancestor of this one
   */
  protected boolean isMaybeRelativeTo(Path ancestorCandidate) {
    return true;
  }

  /**
   * Returns true if this directory is top-level, i.e. it is its own parent.
   *
   * <p>When canonicalizing paths the ".." segment of a top-level directory always resolves to the
   * directory itself.
   *
   * <p>On Unix, a top-level directory would be just the filesystem root ("/), on Windows it would
   * be the filesystem root and the volume roots.
   */
  protected boolean isTopLevelDirectory() {
    return isRootDirectory();
  }

  /**
   * Returns the child path named name, or creates such a path (and caches it)
   * if it doesn't already exist.
   */
  private Path getCachedChildPath(String childName) {
    return getCachedChildPath(fileSystem.getPathFactory().translatePath(this, childName));
  }

  private static Path getCachedChildPath(PathFactory.TranslatedPath translated) {
    Path parent = translated.parent;
    // We get a canonical instance since 'children' is an IdentityHashMap.
    String childName = StringCanonicalizer.intern(translated.child);
    if (!translated.cacheable) {
      // Non-cacheable children won't show up in `children` so applyToChildren won't run for these.
      return parent.createChildPath(childName);
    }
    // We use double-checked locking so that we only hold the lock when we might need to mutate the
    // 'children' variable. 'children' will never become null if it's already non-null, so we only
    // need to worry about the case where it's currently null and we race with another thread
    // executing getCachedChildPath(<doesn't matter>) trying to set 'children' to a non-null value.
    if (parent.children == null) {
      synchronized (parent) {
        if (parent.children == null) {
          // 66% of Paths have size == 1, 80% <= 2
          parent.children = new IdentityHashMap<>(1);
        }
      }
    }
    synchronized (parent) {
      Reference<Path> childRef = parent.children.get(childName);
      Path child;
      if (childRef == null || (child = childRef.get()) == null) {
        child = parent.createChildPath(childName);
        parent.children.put(childName, new PathWeakReferenceForCleanup(child, REFERENCE_QUEUE));
      }
      return child;
    }
  }

  /**
   * Applies the specified function to each {@link Path} that is an existing direct
   * descendant of this one.  The Predicate is evaluated only for its
   * side-effects.
   *
   * <p>This function exists to hide the "children" field, whose complex
   * synchronization and identity requirements are too unsafe to be exposed to
   * subclasses.  For example, the "children" field must be synchronized for
   * the duration of any iteration over it; it may be null; and references
   * within it may be stale, and must be ignored.
   */
  protected synchronized void applyToChildren(Predicate<Path> function) {
    if (children != null) {
      for (Reference<Path> childRef : children.values()) {
        Path child = childRef.get();
        if (child != null) {
          function.apply(child);
        }
      }
    }
  }

  /**
   * Returns whether this path is recursively "under" {@code prefix} - that is,
   * whether {@code path} is a prefix of this path.
   *
   * <p>This method returns {@code true} when called with this path itself. This
   * method acts independently of the existence of files or folders.
   *
   * @param prefix a path which may or may not be a prefix of this path
   */
  public boolean startsWith(Path prefix) {
    Path n = this;
    for (int i = 0, len = depth - prefix.depth; i < len; i++) {
      n = n.getParentDirectory();
    }
    return prefix.equals(n);
  }

  /**
   * Computes a string representation of this path, and writes it to the given string builder. Only
   * called locally with a new instance.
   */
  protected void buildPathString(StringBuilder result) {
    if (isRootDirectory()) {
      result.append(PathFragment.ROOT_DIR);
    } else {
      parent.buildPathString(result);
      if (!parent.isRootDirectory()) {
        result.append(PathFragment.SEPARATOR_CHAR);
      }
      result.append(name);
    }
  }

  /**
   * Returns the path as a string.
   */
  public String getPathString() {
    // Profile driven optimization:
    // Preallocate a size determined by the depth, so that
    // we do not have to expand the capacity of the StringBuilder
    StringBuilder builder = new StringBuilder(depth * 20);
    buildPathString(builder);
    return builder.toString();
  }

  /**
   * Returns the path as a string.
   */
  @Override
  public String toString() {
    return getPathString();
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Path)) {
      return false;
    }

    Path otherPath = (Path) other;
    if (hashCode != otherPath.hashCode) {
      return false;
    }

    if (!fileSystem.equals(otherPath.fileSystem)) {
      return false;
    }

    if (fileSystem.isFilePathCaseSensitive()) {
      return name.equals(otherPath.name)
          && Objects.equals(parent, otherPath.parent);
    } else {
      return name.toLowerCase().equals(otherPath.name.toLowerCase())
          && Objects.equals(parent, otherPath.parent);
    }
  }

  /**
   * Returns a string of debugging information associated with this path.
   * The results are unspecified and MUST NOT be interpreted programmatically.
   */
  protected String toDebugString() {
    return "";
  }

  /**
   * Returns a path representing the parent directory of this path,
   * or null iff this Path represents the root of the filesystem.
   *
   * <p>Note: This method normalises ".."  and "." path segments by string
   * processing, not by directory lookups.
   */
  public Path getParentDirectory() {
    return parent;
  }

  /**
   * Returns true iff this path denotes an existing file of any kind. Follows
   * symbolic links.
   */
  public boolean exists() {
    return fileSystem.exists(this, true);
  }

  /**
   * Returns true iff this path denotes an existing file of any kind.
   *
   * @param followSymlinks if {@link Symlinks#FOLLOW}, and this path denotes a
   *        symbolic link, the link is dereferenced until a file other than a
   *        symbolic link is found
   */
  public boolean exists(Symlinks followSymlinks) {
    return fileSystem.exists(this, followSymlinks.toBoolean());
  }

  /**
   * Returns a new, immutable collection containing the names of all entities
   * within the directory denoted by the current path. Follows symbolic links.
   *
   * @throws FileNotFoundException If the directory is not found
   * @throws IOException If the path does not denote a directory
   */
  public Collection<Path> getDirectoryEntries() throws IOException, FileNotFoundException {
    return fileSystem.getDirectoryEntries(this);
  }

  /**
   * Returns a collection of the names and types of all entries within the directory
   * denoted by the current path.  Follows symbolic links if {@code followSymlinks} is true.
   * Note that the order of the returned entries is not guaranteed.
   *
   * @param followSymlinks whether to follow symlinks or not
   *
   * @throws FileNotFoundException If the directory is not found
   * @throws IOException If the path does not denote a directory
   */
  public Collection<Dirent> readdir(Symlinks followSymlinks) throws IOException {
    return fileSystem.readdir(this, followSymlinks.toBoolean());
  }

  /**
   * Returns a new, immutable collection containing the names of all entities
   * within the directory denoted by the current path, for which the given
   * predicate is true.
   *
   * @throws FileNotFoundException If the directory is not found
   * @throws IOException If the path does not denote a directory
   */
  public Collection<Path> getDirectoryEntries(Predicate<? super Path> predicate)
      throws IOException, FileNotFoundException {
    return ImmutableList.<Path>copyOf(Iterables.filter(getDirectoryEntries(), predicate));
  }

  /**
   * Returns the status of a file, following symbolic links.
   *
   * @throws IOException if there was an error obtaining the file status. Note,
   *         some implementations may defer the I/O, and hence the throwing of
   *         the exception, until the accessor methods of {@code FileStatus} are
   *         called.
   */
  public FileStatus stat() throws IOException {
    return fileSystem.stat(this, true);
  }

  /**
   * Like stat(), but returns null on file-nonexistence instead of throwing.
   */
  public FileStatus statNullable() {
    return statNullable(Symlinks.FOLLOW);
  }

  /**
   * Like stat(), but returns null on file-nonexistence instead of throwing.
   */
  public FileStatus statNullable(Symlinks symlinks) {
    return fileSystem.statNullable(this, symlinks.toBoolean());
  }

  /**
   * Returns the status of a file, optionally following symbolic links.
   *
   * @param followSymlinks if {@link Symlinks#FOLLOW}, and this path denotes a
   *        symbolic link, the link is dereferenced until a file other than a
   *        symbolic link is found
   * @throws IOException if there was an error obtaining the file status. Note,
   *         some implementations may defer the I/O, and hence the throwing of
   *         the exception, until the accessor methods of {@code FileStatus} are
   *         called
   */
  public FileStatus stat(Symlinks followSymlinks) throws IOException {
    return fileSystem.stat(this, followSymlinks.toBoolean());
  }

  /**
   * Like {@link #stat}, but may return null if the file is not found (corresponding to
   * {@code ENOENT} and {@code ENOTDIR} in Unix's stat(2) function) instead of throwing. Follows
   * symbolic links.
   */
  public FileStatus statIfFound() throws IOException {
    return fileSystem.statIfFound(this, true);
  }

  /**
   * Like {@link #stat}, but may return null if the file is not found (corresponding to
   * {@code ENOENT} and {@code ENOTDIR} in Unix's stat(2) function) instead of throwing.
   *
   * @param followSymlinks if {@link Symlinks#FOLLOW}, and this path denotes a
   *        symbolic link, the link is dereferenced until a file other than a
   *        symbolic link is found
   */
  public FileStatus statIfFound(Symlinks followSymlinks) throws IOException {
    return fileSystem.statIfFound(this, followSymlinks.toBoolean());
  }


  /**
   * Returns true iff this path denotes an existing directory. Follows symbolic
   * links.
   */
  public boolean isDirectory() {
    return fileSystem.isDirectory(this, true);
  }

  /**
   * Returns true iff this path denotes an existing directory.
   *
   * @param followSymlinks if {@link Symlinks#FOLLOW}, and this path denotes a
   *        symbolic link, the link is dereferenced until a file other than a
   *        symbolic link is found
   */
  public boolean isDirectory(Symlinks followSymlinks) {
    return fileSystem.isDirectory(this, followSymlinks.toBoolean());
  }

  /**
   * Returns true iff this path denotes an existing regular or special file.
   * Follows symbolic links.
   *
   * <p>For our purposes, "file" includes special files (socket, fifo, block or
   * char devices) too; it excludes symbolic links and directories.
   */
  public boolean isFile() {
    return fileSystem.isFile(this, true);
  }

  /**
   * Returns true iff this path denotes an existing regular or special file.
   *
   * <p>For our purposes, a "file" includes special files (socket, fifo, block
   * or char devices) too; it excludes symbolic links and directories.
   *
   * @param followSymlinks if {@link Symlinks#FOLLOW}, and this path denotes a
   *        symbolic link, the link is dereferenced until a file other than a
   *        symbolic link is found.
   */
  public boolean isFile(Symlinks followSymlinks) {
    return fileSystem.isFile(this, followSymlinks.toBoolean());
  }

  /**
   * Returns true iff this path denotes an existing special file (e.g. fifo).
   * Follows symbolic links.
   */
  public boolean isSpecialFile() {
    return fileSystem.isSpecialFile(this, true);
  }

  /**
   * Returns true iff this path denotes an existing special file (e.g. fifo).
   *
   * @param followSymlinks if {@link Symlinks#FOLLOW}, and this path denotes a
   *        symbolic link, the link is dereferenced until a path other than a
   *        symbolic link is found.
   */
  public boolean isSpecialFile(Symlinks followSymlinks) {
    return fileSystem.isSpecialFile(this, followSymlinks.toBoolean());
  }

  /**
   * Returns true iff this path denotes an existing symbolic link. Does not
   * follow symbolic links.
   */
  public boolean isSymbolicLink() {
    return fileSystem.isSymbolicLink(this);
  }

  /**
   * Returns the last segment of this path, or "/" for the root directory.
   */
  public String getBaseName() {
    return name;
  }

  /**
   * Interprets the name of a path segment relative to the current path and
   * returns the result.
   *
   * <p>This is a purely syntactic operation, i.e. it does no I/O, it does not
   * validate the existence of any path, nor resolve symbolic links. If 'prefix'
   * is not canonical, then a 'name' of '..' will be interpreted incorrectly.
   *
   * @precondition segment contains no slashes.
   */
  private Path getCanonicalPath(String segment) {
    if (segment.equals(".") || segment.isEmpty()) {
      return this; // that's a noop
    } else if (segment.equals("..")) {
      // top-level directory's parent is root, when canonicalising:
      return isTopLevelDirectory() ? this : parent;
    } else {
      return getCachedChildPath(segment);
    }
  }

  /**
   * Returns the path formed by appending the single non-special segment
   * "baseName" to this path.
   *
   * <p>You should almost always use {@link #getRelative} instead, which has
   * the same performance characteristics if the given name is a valid base
   * name, and which also works for '.', '..', and strings containing '/'.
   *
   * @throws IllegalArgumentException if {@code baseName} is not a valid base
   *     name according to {@link FileSystemUtils#checkBaseName}
   */
  public Path getChild(String baseName) {
    FileSystemUtils.checkBaseName(baseName);
    return getCachedChildPath(baseName);
  }

  protected Path getRootForRelativePathComputation(PathFragment suffix) {
    return suffix.isAbsolute() ? fileSystem.getRootDirectory() : this;
  }

  /**
   * Returns the path formed by appending the relative or absolute path fragment
   * {@code suffix} to this path.
   *
   * <p>If suffix is absolute, the current path will be ignored; otherwise, they
   * will be combined. Up-level references ("..") cause the preceding path
   * segment to be elided; this interpretation is only correct if the base path
   * is canonical.
   */
  public Path getRelative(PathFragment suffix) {
    Path result = getRootForRelativePathComputation(suffix);
    for (String segment : suffix.segments()) {
      result = result.getCanonicalPath(segment);
    }
    return result;
  }

  /**
   * Returns the path formed by appending the relative or absolute string
   * {@code path} to this path.
   *
   * <p>If the given path string is absolute, the current path will be ignored;
   * otherwise, they will be combined. Up-level references ("..") cause the
   * preceding path segment to be elided.
   *
   * <p>This is a purely syntactic operation, i.e. it does no I/O, it does not
   * validate the existence of any path, nor resolve symbolic links.
   */
  public Path getRelative(String path) {
    // Fast path for valid base names.
    if ((path.length() == 0) || (path.equals("."))) {
      return this;
    } else if (path.equals("..")) {
      return isTopLevelDirectory() ? this : parent;
    } else if (path.indexOf('/') != -1) {
      return getRelative(new PathFragment(path));
    } else if (path.indexOf(PathFragment.EXTRA_SEPARATOR_CHAR) != -1) {
      return getRelative(new PathFragment(path));
    } else {
      return getCachedChildPath(path);
    }
  }

  protected final String[] getSegments() {
    String[] resultSegments = new String[depth];
    Path currentPath = this;
    for (int pos = depth - 1; pos >= 0; pos--) {
      resultSegments[pos] = currentPath.getBaseName();
      currentPath = currentPath.getParentDirectory();
    }
    return resultSegments;
  }

  /** Returns an absolute PathFragment representing this path. */
  public PathFragment asFragment() {
    return new PathFragment('\0', true, getSegments());
  }

  /**
   * Returns a relative path fragment to this path, relative to {@code
   * ancestorDirectory}. {@code ancestorDirectory} must be on the same
   * filesystem as this path. (Currently, both this path and "ancestorDirectory"
   * must be absolute, though this restriction could be loosened.)
   * <p>
   * <code>x.relativeTo(z) == y</code> implies
   * <code>z.getRelative(y.getPathString()) == x</code>.
   * <p>
   * For example, <code>"/foo/bar/wiz".relativeTo("/foo")</code> returns
   * <code>"bar/wiz"</code>.
   *
   * @throws IllegalArgumentException if this path is not beneath {@code
   *         ancestorDirectory} or if they are not part of the same filesystem
   */
  public PathFragment relativeTo(Path ancestorPath) {
    checkSameFilesystem(ancestorPath);

    if (isMaybeRelativeTo(ancestorPath)) {
      // Fast path: when otherPath is the ancestor of this path
      int resultSegmentCount = depth - ancestorPath.depth;
      if (resultSegmentCount >= 0) {
        String[] resultSegments = new String[resultSegmentCount];
        Path currentPath = this;
        for (int pos = resultSegmentCount - 1; pos >= 0; pos--) {
          resultSegments[pos] = currentPath.getBaseName();
          currentPath = currentPath.getParentDirectory();
        }
        if (ancestorPath.equals(currentPath)) {
          return new PathFragment('\0', false, resultSegments);
        }
      }
    }

    throw new IllegalArgumentException("Path " + this + " is not beneath " + ancestorPath);
  }

  /**
   * Checks that "this" and "that" are paths on the same filesystem.
   */
  protected void checkSameFilesystem(Path that) {
    if (this.fileSystem != that.fileSystem) {
      throw new IllegalArgumentException("Files are on different filesystems: "
          + this + ", " + that);
    }
  }

  /**
   * Returns an output stream to the file denoted by the current path, creating
   * it and truncating it if necessary.  The stream is opened for writing.
   *
   * @throws FileNotFoundException If the file cannot be found or created.
   * @throws IOException If a different error occurs.
   */
  public OutputStream getOutputStream() throws IOException, FileNotFoundException {
    return getOutputStream(false);
  }

  /**
   * Returns an output stream to the file denoted by the current path, creating
   * it and truncating it if necessary.  The stream is opened for writing.
   *
   * @param append whether to open the file in append mode.
   * @throws FileNotFoundException If the file cannot be found or created.
   * @throws IOException If a different error occurs.
   */
  public OutputStream getOutputStream(boolean append) throws IOException, FileNotFoundException {
    return fileSystem.getOutputStream(this, append);
  }

  /**
   * Creates a directory with the name of the current path, not following
   * symbolic links.  Returns normally iff the directory exists after the call:
   * true if the directory was created by this call, false if the directory was
   * already in existence.  Throws an exception if the directory could not be
   * created for any reason.
   *
   * @throws IOException if the directory creation failed for any reason
   */
  public boolean createDirectory() throws IOException {
    return fileSystem.createDirectory(this);
  }

  /**
   * Creates a symbolic link with the name of the current path, following
   * symbolic links. The referent of the created symlink is is the absolute path
   * "target"; it is not possible to create relative symbolic links via this
   * method.
   *
   * @throws IOException if the creation of the symbolic link was unsuccessful
   *         for any reason
   */
  public void createSymbolicLink(Path target) throws IOException {
    checkSameFilesystem(target);
    fileSystem.createSymbolicLink(this, target.asFragment());
  }

  /**
   * Creates a symbolic link with the name of the current path, following
   * symbolic links. The referent of the created symlink is is the path fragment
   * "target", which may be absolute or relative.
   *
   * @throws IOException if the creation of the symbolic link was unsuccessful
   *         for any reason
   */
  public void createSymbolicLink(PathFragment target) throws IOException {
    fileSystem.createSymbolicLink(this, target);
  }

  /**
   * Returns the target of the current path, which must be a symbolic link. The link contents are
   * returned exactly, and may contain an absolute or relative path. Analogous to readlink(2).
   *
   * <p>Note: for {@link FileSystem}s where {@link FileSystem#supportsSymbolicLinksNatively()}
   * returns false, this method will throw an {@link UnsupportedOperationException} if the link
   * points to a non-existent file.
   *
   * @return the content (i.e. target) of the symbolic link
   * @throws IOException if the current path is not a symbolic link, or the contents of the link
   *     could not be read for any reason
   */
  public PathFragment readSymbolicLink() throws IOException {
    return fileSystem.readSymbolicLink(this);
  }

  /**
   * If the current path is a symbolic link, returns the target of this symbolic link. The
   * semantics are intentionally left underspecified otherwise to permit efficient implementations.
   *
   * @return the content (i.e. target) of the symbolic link
   * @throws IOException if the current path is not a symbolic link, or the
   *         contents of the link could not be read for any reason
   */
  public PathFragment readSymbolicLinkUnchecked() throws IOException {
    return fileSystem.readSymbolicLinkUnchecked(this);
  }

  /**
   * Create a hard link for the current path.
   *
   * @param link the path of the new link
   * @throws IOException if there was an error executing {@link FileSystem#createHardLink}
   */
  public void createHardLink(Path link) throws IOException {
    fileSystem.createHardLink(link, this);
  }

  /**
   * Returns the canonical path for this path, by repeatedly replacing symbolic
   * links with their referents. Analogous to realpath(3).
   *
   * @return the canonical path for this path
   * @throws IOException if any symbolic link could not be resolved, or other
   *         error occurred (for example, the path does not exist)
   */
  public Path resolveSymbolicLinks() throws IOException {
    return fileSystem.resolveSymbolicLinks(this);
  }

  /**
   * Renames the file denoted by the current path to the location "target", not
   * following symbolic links.
   *
   * <p>Files cannot be atomically renamed across devices; copying is required.
   * Use {@link FileSystemUtils#copyFile} followed by {@link Path#delete}.
   *
   * @throws IOException if the rename failed for any reason
   */
  public void renameTo(Path target) throws IOException {
    checkSameFilesystem(target);
    fileSystem.renameTo(this, target);
  }

  /**
   * Returns the size in bytes of the file denoted by the current path,
   * following symbolic links.
   *
   * <p>The size of a directory or special file is undefined and should not be used.
   *
   * @throws FileNotFoundException if the file denoted by the current path does
   *         not exist
   * @throws IOException if the file's metadata could not be read, or some other
   *         error occurred
   */
  public long getFileSize() throws IOException, FileNotFoundException {
    return fileSystem.getFileSize(this, true);
  }

  /**
   * Returns the size in bytes of the file denoted by the current path.
   *
   * <p>The size of directory or special file is undefined. The size of a symbolic
   * link is the length of the name of its referent.
   *
   * @param followSymlinks if {@link Symlinks#FOLLOW}, and this path denotes a
   *        symbolic link, the link is deferenced until a file other than a
   *        symbol link is found
   * @throws FileNotFoundException if the file denoted by the current path does
   *         not exist
   * @throws IOException if the file's metadata could not be read, or some other
   *         error occurred
   */
  public long getFileSize(Symlinks followSymlinks) throws IOException, FileNotFoundException {
    return fileSystem.getFileSize(this, followSymlinks.toBoolean());
  }

  /**
   * Deletes the file denoted by this path, not following symbolic links.
   * Returns normally iff the file doesn't exist after the call: true if this
   * call deleted the file, false if the file already didn't exist.  Throws an
   * exception if the file could not be deleted for any reason.
   *
   * @return true iff the file was actually deleted by this call
   * @throws IOException if the deletion failed but the file was present prior
   *         to the call
   */
  public boolean delete() throws IOException {
    return fileSystem.delete(this);
  }

  /**
   * Returns the last modification time of the file, in milliseconds since the
   * UNIX epoch, of the file denoted by the current path, following symbolic
   * links.
   *
   * <p>Caveat: many filesystems store file times in seconds, so do not rely on
   * the millisecond precision.
   *
   * @throws IOException if the operation failed for any reason
   */
  public long getLastModifiedTime() throws IOException {
    return fileSystem.getLastModifiedTime(this, true);
  }

  /**
   * Returns the last modification time of the file, in milliseconds since the
   * UNIX epoch, of the file denoted by the current path.
   *
   * <p>Caveat: many filesystems store file times in seconds, so do not rely on
   * the millisecond precision.
   *
   * @param followSymlinks if {@link Symlinks#FOLLOW}, and this path denotes a
   *        symbolic link, the link is dereferenced until a file other than a
   *        symbolic link is found
   * @throws IOException if the modification time for the file could not be
   *         obtained for any reason
   */
  public long getLastModifiedTime(Symlinks followSymlinks) throws IOException {
    return fileSystem.getLastModifiedTime(this, followSymlinks.toBoolean());
  }

  /**
   * Sets the modification time of the file denoted by the current path. Follows
   * symbolic links. If newTime is -1, the current time according to the kernel
   * is used; this may differ from the JVM's clock.
   *
   * <p>Caveat: many filesystems store file times in seconds, so do not rely on
   * the millisecond precision.
   *
   * @param newTime time, in milliseconds since the UNIX epoch, or -1L, meaning
   *        use the kernel's current time
   * @throws IOException if the modification time for the file could not be set
   *         for any reason
   */
  public void setLastModifiedTime(long newTime) throws IOException {
    fileSystem.setLastModifiedTime(this, newTime);
  }

  /**
   * Returns value of the given extended attribute name or null if attribute does not exist or
   * file system does not support extended attributes. Follows symlinks.
   */
  public byte[] getxattr(String name) throws IOException {
    return fileSystem.getxattr(this, name);
  }

  /**
   * Gets a fast digest for the given path, or {@code null} if there isn't one available. The
   * digest should be suitable for detecting changes to the file.
   */
  public byte[] getFastDigest() throws IOException {
    return fileSystem.getFastDigest(this);
  }

  /**
   * Gets a fast digest for the given path, or {@code null} if there isn't one available. The
   * digest should be suitable for detecting changes to the file.
   */
  public byte[] getFastDigest(HashFunction hashFunction) throws IOException {
    return fileSystem.getFastDigest(this, hashFunction);
  }

  /**
   * Returns whether the given digest is a valid digest for the default system digest function.
   */
  public boolean isValidDigest(byte[] digest) {
    return fileSystem.isValidDigest(digest);
  }

  /**
   * Returns the MD5 digest of the file denoted by the current path, following
   * symbolic links.
   *
   * <p>This method runs in O(n) time where n is the length of the file, but
   * certain implementations may be much faster than the worst case.
   *
   * @return a new 16-byte array containing the file's MD5 digest
   * @throws IOException if the MD5 digest could not be computed for any reason
   */
  public byte[] getMD5Digest() throws IOException {
    return fileSystem.getMD5Digest(this);
  }

  /**
   * Returns the SHA1 digest of the file denoted by the current path, following
   * symbolic links.
   *
   * <p>This method runs in O(n) time where n is the length of the file, but
   * certain implementations may be much faster than the worst case.
   *
   * @return a new 20-byte array containing the file's SHA1 digest
   * @throws IOException if the SHA1 digest could not be computed for any reason
   */
  public byte[] getSHA1Digest() throws IOException {
    return fileSystem.getSHA1Digest(this);
  }

  /**
   * Returns the digest of the file denoted by the current path,
   * following symbolic links.
   *
   * @return a new byte array containing the file's digest
   * @throws IOException if the digest could not be computed for any reason
   */
  public byte[] getDigest() throws IOException {
    return fileSystem.getDigest(this);
  }

  /**
   * Returns the digest of the file denoted by the current path and digest function,
   * following symbolic links.
   *
   * @return a new byte array containing the file's digest
   * @throws IOException if the digest could not be computed for any reason
   */
  public byte[] getDigest(HashFunction hashFunction) throws IOException {
    return fileSystem.getDigest(this, hashFunction);
  }

  /**
   * Opens the file denoted by this path, following symbolic links, for reading,
   * and returns an input stream to it.
   *
   * @throws IOException if the file was not found or could not be opened for
   *         reading
   */
  public InputStream getInputStream() throws IOException {
    return fileSystem.getInputStream(this);
  }

  /**
   * Returns a java.io.File representation of this path.
   *
   * <p>Caveat: the result may be useless if this path's getFileSystem() is not
   * the UNIX filesystem.
   */
  public File getPathFile() {
    return new File(getPathString());
  }

  /**
   * Returns true if the file denoted by the current path, following symbolic
   * links, is writable for the current user.
   *
   * @throws FileNotFoundException if the file does not exist, a dangling
   *         symbolic link was encountered, or the file's metadata could not be
   *         read
   */
  public boolean isWritable() throws IOException, FileNotFoundException {
    return fileSystem.isWritable(this);
  }

  /**
   * Sets the read permissions of the file denoted by the current path,
   * following symbolic links. Permissions apply to the current user.
   *
   * @param readable if true, the file is set to readable; otherwise the file is
   *        made non-readable
   * @throws FileNotFoundException if the file does not exist
   * @throws IOException If the action cannot be taken (ie. permissions)
   */
  public void setReadable(boolean readable) throws IOException, FileNotFoundException {
    fileSystem.setReadable(this, readable);
  }

  /**
   * Sets the write permissions of the file denoted by the current path,
   * following symbolic links. Permissions apply to the current user.
   *
   * <p>TODO(bazel-team): (2009) what about owner/group/others?
   *
   * @param writable if true, the file is set to writable; otherwise the file is
   *        made non-writable
   * @throws FileNotFoundException if the file does not exist
   * @throws IOException If the action cannot be taken (ie. permissions)
   */
  public void setWritable(boolean writable) throws IOException, FileNotFoundException {
    fileSystem.setWritable(this, writable);
  }

  /**
   * Returns true iff the file specified by the current path, following symbolic
   * links, is executable by the current user.
   *
   * @throws FileNotFoundException if the file does not exist or a dangling
   *         symbolic link was encountered
   * @throws IOException if some other I/O error occurred
   */
  public boolean isExecutable() throws IOException, FileNotFoundException {
    return fileSystem.isExecutable(this);
  }

  /**
   * Returns true iff the file specified by the current path, following symbolic
   * links, is readable by the current user.
   *
   * @throws FileNotFoundException if the file does not exist or a dangling
   *         symbolic link was encountered
   * @throws IOException if some other I/O error occurred
   */
  public boolean isReadable() throws IOException, FileNotFoundException {
    return fileSystem.isReadable(this);
  }

  /**
   * Sets the execute permission on the file specified by the current path,
   * following symbolic links. Permissions apply to the current user.
   *
   * @throws FileNotFoundException if the file does not exist or a dangling
   *         symbolic link was encountered
   * @throws IOException if the metadata change failed, for example because of
   *         permissions
   */
  public void setExecutable(boolean executable) throws IOException, FileNotFoundException {
    fileSystem.setExecutable(this, executable);
  }

  /**
   * Sets the permissions on the file specified by the current path, following
   * symbolic links. If permission changes on this path's {@link FileSystem} are
   * slow (e.g. one syscall per change), this method should aim to be faster
   * than setting each permission individually. If this path's
   * {@link FileSystem} does not support group and others permissions, those
   * bits will be ignored.
   *
   * @throws FileNotFoundException if the file does not exist or a dangling
   *         symbolic link was encountered
   * @throws IOException if the metadata change failed, for example because of
   *         permissions
   */
  public void chmod(int mode) throws IOException {
    fileSystem.chmod(this, mode);
  }

  public void prefetchPackageAsync(int maxDirs) {
    fileSystem.prefetchPackageAsync(this, maxDirs);
  }

  /**
   * Compare Paths of the same file system using their PathFragments.
   *
   * <p>Paths from different filesystems will be compared using the identity
   * hash code of their respective filesystems.
   */
  @Override
  public int compareTo(Path o) {
    // Fast-path.
    if (equals(o)) {
      return 0;
    }

    // If they are on different file systems, the file system decides the ordering.
    FileSystem otherFs = o.getFileSystem();
    if (!fileSystem.equals(otherFs)) {
      int thisFileSystemHash = System.identityHashCode(fileSystem);
      int otherFileSystemHash = System.identityHashCode(otherFs);
      if (thisFileSystemHash < otherFileSystemHash) {
        return -1;
      } else if (thisFileSystemHash > otherFileSystemHash) {
        return 1;
      } else {
        // TODO(bazel-team): Add a name to every file system to be used here.
        return 0;
      }
    }

    // Equal file system, but different paths, because of the canonicalization.
    // We expect to often compare Paths that are very similar, for example for files in the same
    // directory. This can be done efficiently by going up segment by segment until we get the
    // identical path (canonicalization again), and then just compare the immediate child segments.
    // Overall this is much faster than creating PathFragment instances, and comparing those, which
    // requires us to always go up to the top-level directory and copy all segments into a new
    // string array.
    // This was previously showing up as a hotspot in a profile of globbing a large directory.
    Path a = this;
    Path b = o;
    int maxDepth = Math.min(a.depth, b.depth);
    while (a.depth > maxDepth) {
      a = a.getParentDirectory();
    }
    while (b.depth > maxDepth) {
      b = b.getParentDirectory();
    }
    // One is the child of the other.
    if (a.equals(b)) {
      // If a is the same as this, this.depth must be less than o.depth.
      return equals(a) ? -1 : 1;
    }
    Path previousa;
    Path previousb;
    do {
      previousa = a;
      previousb = b;
      a = a.getParentDirectory();
      b = b.getParentDirectory();
    } while (!a.equals(b)); // This has to happen eventually.
    return previousa.name.compareTo(previousb.name);
  }
}
