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

package com.google.devtools.build.buildjar.jarhelper;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * A class for creating Jar files. Allows normalization of Jar entries by setting their timestamp to
 * the DOS epoch. All Jar entries are sorted alphabetically.
 */
public class JarCreator extends JarHelper {

  // Map from Jar entry names to files. Use TreeMap so we can establish a canonical order for the
  // entries regardless in what order they get added.
  private final TreeMap<String, Path> jarEntries = new TreeMap<>();
  private String manifestFile;
  private String mainClass;

  public JarCreator(String fileName) {
    super(fileName);
  }

  /**
   * Adds an entry to the Jar file, normalizing the name.
   *
   * @param entryName the name of the entry in the Jar file
   * @param path the path of the input for the entry
   * @return true iff a new entry was added
   */
  public boolean addEntry(String entryName, Path path) {
    if (entryName.startsWith("/")) {
      entryName = entryName.substring(1);
    } else if (entryName.startsWith("./")) {
      entryName = entryName.substring(2);
    }
    return jarEntries.put(entryName, path) == null;
  }

  /**
   * Adds an entry to the Jar file, normalizing the name.
   *
   * @param entryName the name of the entry in the Jar file
   * @param fileName the name of the input file for the entry
   * @return true iff a new entry was added
   */
  public boolean addEntry(String entryName, String fileName) {
    return addEntry(entryName, Paths.get(fileName));
  }

  /**
   * Adds the contents of a directory to the Jar file. All files below this directory will be added
   * to the Jar file using the name relative to the directory as the name for the Jar entry.
   *
   * @param directory the directory to add to the jar
   */
  public void addDirectory(String directory) {
    addDirectory(null, new File(directory));
  }

  /**
   * Adds the contents of a directory to the Jar file. All files below this directory will be added
   * to the Jar file using the prefix and the name relative to the directory as the name for the Jar
   * entry. Always uses '/' as the separator char for the Jar entries.
   *
   * @param prefix the prefix to prepend to every Jar entry name found below the directory
   * @param directory the directory to add to the Jar
   */
  private void addDirectory(String prefix, File directory) {
    File[] files = directory.listFiles();
    if (files != null) {
      for (File file : files) {
        String entryName = prefix != null ? prefix + "/" + file.getName() : file.getName();
        jarEntries.put(entryName, file.toPath());
        if (file.isDirectory()) {
          addDirectory(entryName, file);
        }
      }
    }
  }

  /**
   * Adds a collection of entries to the jar, each with a given source path, and with the resulting
   * file in the root of the jar.
   *
   * <pre>
   * some/long/path.foo => (path.foo, some/long/path.foo)
   * </pre>
   */
  public void addRootEntries(Collection<String> entries) {
    for (String entry : entries) {
      Path path = Paths.get(entry);
      jarEntries.put(path.getFileName().toString(), path);
    }
  }

  /**
   * Sets the main.class entry for the manifest. A value of <code>null</code> (the default) will
   * omit the entry.
   *
   * @param mainClass the fully qualified name of the main class
   */
  public void setMainClass(String mainClass) {
    this.mainClass = mainClass;
  }

  /**
   * Sets filename for the manifest content. If this is set the manifest will be read from this file
   * otherwise the manifest content will get generated on the fly.
   *
   * @param manifestFile the filename of the manifest file.
   */
  public void setManifestFile(String manifestFile) {
    this.manifestFile = manifestFile;
  }

  private byte[] manifestContent() throws IOException {
    Manifest manifest;
    if (manifestFile != null) {
      FileInputStream in = new FileInputStream(manifestFile);
      manifest = new Manifest(in);
    } else {
      manifest = new Manifest();
    }
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    Attributes.Name createdBy = new Attributes.Name("Created-By");
    if (attributes.getValue(createdBy) == null) {
      attributes.put(createdBy, "blaze");
    }
    if (mainClass != null) {
      attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    manifest.write(out);
    return out.toByteArray();
  }

  /**
   * Executes the creation of the Jar file.
   *
   * @throws IOException if the Jar cannot be written or any of the entries cannot be read.
   */
  public void execute() throws IOException {
    out = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(jarFile)));

    // Create the manifest entry in the Jar file
    writeManifestEntry(manifestContent());
    try {
      for (Map.Entry<String, Path> entry : jarEntries.entrySet()) {
        copyEntry(entry.getKey(), entry.getValue());
      }
    } finally {
      out.closeEntry();
      out.close();
    }
  }

  /** A simple way to create Jar file using the JarCreator class. */
  public static void main(String[] args) {
    if (args.length < 1) {
      System.err.println("usage: CreateJar output [root directories]");
      System.exit(1);
    }
    String output = args[0];
    JarCreator createJar = new JarCreator(output);
    for (int i = 1; i < args.length; i++) {
      createJar.addDirectory(args[i]);
    }
    createJar.setCompression(true);
    createJar.setNormalize(true);
    createJar.setVerbose(true);
    long start = System.currentTimeMillis();
    try {
      createJar.execute();
    } catch (IOException e) {
      e.printStackTrace();
      System.exit(1);
    }
    long stop = System.currentTimeMillis();
    System.err.println((stop - start) + "ms.");
  }
}
