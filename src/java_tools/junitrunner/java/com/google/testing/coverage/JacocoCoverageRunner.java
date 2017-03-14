// Copyright 2016 The Bazel Authors. All Rights Reserved.
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

package com.google.testing.coverage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import org.jacoco.agent.rt.IAgent;
import org.jacoco.agent.rt.RT;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.ISourceFileLocator;

/**
 * Runner class used to generate code coverage report when using Jacoco offline instrumentation.
 *
 * <p>The complete list of features available for Jacoco offline instrumentation:
 * http://www.eclemma.org/jacoco/trunk/doc/offline.html
 *
 * <p>The structure is roughly following the canonical Jacoco example:
 * http://www.eclemma.org/jacoco/trunk/doc/examples/java/ReportGenerator.java
 *
 * <p>The following environment variables are expected:
 * JAVA_COVERAGE_FILE - specifies final location of the generated lcov file.
 * JACOCO_METADATA_JAR - specifies jar containing uninstrumented classes to be analyzed.
 */
public class JacocoCoverageRunner {

  private final List<File> classesJars;
  private final InputStream executionData;
  private final File reportFile;
  private ExecFileLoader execFileLoader;

  public JacocoCoverageRunner(InputStream jacocoExec, String reportPath, File... metadataJars) {
    executionData = jacocoExec;
    reportFile = new File(reportPath);

    classesJars = new ArrayList<>();
    for (File metadataJar : metadataJars) {
      classesJars.add(metadataJar);
    }
  }

  public void create() throws IOException {
    // Read the jacoco.exec file. Multiple data files could be merged at this point
    execFileLoader = new ExecFileLoader();
    execFileLoader.load(executionData);

    // Run the structure analyzer on a single class folder or jar file to build up the coverage
    // model. Typically you would create a bundle for each class folder and each jar you want in
    // your report. If you have more than one bundle you may need to add a grouping node to the
    // report. The lcov formatter doesn't seem to care, and we're only using one bundle anyway.
    final IBundleCoverage bundleCoverage = analyzeStructure();

    final Map<String, BranchCoverageDetail> branchDetails = analyzeBranch();
    createReport(bundleCoverage, branchDetails);
  }

  private void createReport(
      final IBundleCoverage bundleCoverage, final Map<String, BranchCoverageDetail> branchDetails)
      throws IOException {
    JacocoLCOVFormatter formatter = new JacocoLCOVFormatter();
    final IReportVisitor visitor = formatter.createVisitor(reportFile, branchDetails);

    // Initialize the report with all of the execution and session information. At this point the
    // report doesn't know about the structure of the report being created.
    visitor.visitInfo(
        execFileLoader.getSessionInfoStore().getInfos(),
        execFileLoader.getExecutionDataStore().getContents());

    // Populate the report structure with the bundle coverage information.
    // Call visitGroup if you need groups in your report.

    // Note the API requires a sourceFileLocator because the HTML and XML formatters display a page
    // of code annotated with coverage information. Having the source files is not actually needed
    // for generating the lcov report...
    visitor.visitBundle(
        bundleCoverage,
        new ISourceFileLocator() {

          @Override
          public Reader getSourceFile(String packageName, String fileName) throws IOException {
            return null;
          }

          @Override
          public int getTabWidth() {
            return 0;
          }
        });

    // Signal end of structure information to allow report to write all information out
    visitor.visitEnd();
  }

  private IBundleCoverage analyzeStructure() throws IOException {
    final CoverageBuilder coverageBuilder = new CoverageBuilder();
    final Analyzer analyzer = new Analyzer(execFileLoader.getExecutionDataStore(), coverageBuilder);
    for (File classesJar : classesJars) {
      analyzer.analyzeAll(classesJar);
    }

    // TODO(bazel-team): Find out where the name of the bundle can pop out in the report.
    return coverageBuilder.getBundle("isthisevenused");
  }

  // Additional pass to process the branch details of the classes
  private Map<String, BranchCoverageDetail> analyzeBranch() throws IOException {
    final BranchDetailAnalyzer analyzer =
        new BranchDetailAnalyzer(execFileLoader.getExecutionDataStore());

    Map<String, BranchCoverageDetail> result = new TreeMap<>();
    for (File classesJar : classesJars) {
      analyzer.analyzeAll(classesJar);
      result.putAll(analyzer.getBranchDetails());
    }
    return result;
  }

  private static String getMainClass(String metadataJar) throws Exception {
    if (metadataJar != null) {
      // Blaze guarantees that JACOCO_METADATA_JAR has a proper manifest with a Main-Class entry.
      try (JarInputStream jarStream = new JarInputStream(new FileInputStream(metadataJar))) {
        return jarStream.getManifest().getMainAttributes().getValue("Main-Class");
      }
    } else {
      // If metadataJar was not set, we're running inside a deploy jar. We have to open the manifest
      // and read the value of "Precoverage-Class", set by Blaze. Note ClassLoader#getResource()
      // will only return the first result, most likely a manifest from the bootclasspath.
      Enumeration<URL> manifests =
          JacocoCoverageRunner.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
      while (manifests.hasMoreElements()) {
        Manifest manifest = new Manifest(manifests.nextElement().openStream());
        Attributes attributes = manifest.getMainAttributes();
        String className = attributes.getValue("Coverage-Main-Class");
        if (className != null) {
          return className;
        }
      }
      throw new IllegalStateException(
          "JACOCO_METADATA_JAR environment variable is not set, and no"
              + " META-INF/MANIFEST.MF on the classpath has a Coverage-Main-Class attribute. "
              + " Cannot determine the name of the main class for the code under test.");
    }
  }

  private static String getUniquePath(String pathTemplate, String suffix) throws IOException {
    // If pathTemplate is null, we're likely executing from a deploy jar and the test framework
    // did not properly set the environment for coverage reporting. This alone is not a reason for
    // throwing an exception, we're going to run anyway and write the coverage data to a temporary,
    // throw-away file.
    if (pathTemplate == null) {
      return File.createTempFile("coverage", suffix).getPath();
    } else {
      // Blaze sets the path template to a file with the .dat extension. lcov_merger matches all
      // files having '.dat' in their name, so instead of appending we change the extension.
      File absolutePathTemplate = new File(pathTemplate).getAbsoluteFile();
      String prefix = absolutePathTemplate.getName();
      int lastDot = prefix.lastIndexOf('.');
      if (lastDot != -1) {
        prefix = prefix.substring(0, lastDot);
      }
      return File.createTempFile(prefix, suffix, absolutePathTemplate.getParentFile()).getPath();
    }
  }

  public static void main(String[] args) throws Exception {
    final String metadataJar = System.getenv("JACOCO_METADATA_JAR");
    final String coverageReportBase = System.getenv("JAVA_COVERAGE_FILE");

    // Disable Jacoco's default output mechanism, which runs as a shutdown hook. We generate the
    // report in our own shutdown hook below, and we want to avoid the data race (shutdown hooks are
    // not guaranteed any particular order). Note that also by default, Jacoco appends coverage
    // data, which can have surprising results if running tests locally or somehow encountering
    // the previous .exec file.
    System.setProperty("jacoco-agent.output", "none");

    // We have no use for this sessionId property, but leaving it blank results in a DNS lookup
    // at runtime. A minor annoyance: the documentation insists the property name is "sessionId",
    // however on closer inspection of the source code, it turns out to be "sessionid"...
    System.setProperty("jacoco-agent.sessionid", "default");

    // A JVM shutdown hook has a fixed amount of time (OS-dependent) before it is terminated.
    // For our purpose, it's more than enough to scan through the instrumented jar and match up
    // the bytecode with the coverage data. It wouldn't be enough for scanning the entire classpath,
    // or doing something else terribly inefficient.
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              public void run() {
                try {
                  // If the test spawns multiple JVMs, they will race to write to the same files. We
                  // need to generate unique paths for each execution. lcov_merger simply collects
                  // all the .dat files in the current directory anyway, so we don't need to worry
                  // about merging them.
                  String coverageReport = getUniquePath(coverageReportBase, ".dat");
                  String coverageData = getUniquePath(coverageReportBase, ".exec");

                  // Get a handle on the Jacoco Agent and write out the coverage data. Other options
                  // included talking to the agent via TCP (useful when gathering coverage from
                  // multiple JVMs), or via JMX (the agent's MXBean is called
                  // 'org.jacoco:type=Runtime'). As we're running in the same JVM, these options
                  // seemed overkill, we can just refer to the Jacoco runtime as RT.
                  // See http://www.eclemma.org/jacoco/trunk/doc/agent.html for all the options
                  // available.
                  ByteArrayInputStream dataInputStream;
                  try {
                    IAgent agent = RT.getAgent();
                    byte[] data = agent.getExecutionData(false);
                    try (FileOutputStream fs = new FileOutputStream(coverageData, true)) {
                      fs.write(data);
                    }
                    // We append to the output file, but run report generation only for the coverage
                    // data from this JVM. The output file may contain data from other
                    // subprocesses, etc.
                    dataInputStream = new ByteArrayInputStream(data);
                  } catch (IllegalStateException e) {
                    // In this case, we didn't execute a single instrumented file, so the agent
                    // isn't live. There's no coverage to report, but it's otherwise a successful
                    // invocation.
                    dataInputStream = new ByteArrayInputStream(new byte[0]);
                  }

                  if (metadataJar != null) {
                    // Disable coverage in this case. The build system should report an error or
                    // warning if this happens. It's too late at this point.
                    new JacocoCoverageRunner(dataInputStream, coverageReport, new File(metadataJar))
                        .create();
                  }
                } catch (IOException e) {
                  e.printStackTrace();
                  Runtime.getRuntime().halt(1);
                }
              }
            });

    // Another option would be to run the tests in a separate JVM, let Jacoco dump out the coverage
    // data, wait for the subprocess to finish and then generate the lcov report. The only benefit
    // of doing this is not being constrained by the hard 5s limit of the shutdown hook. Setting up
    // the subprocess to match all JVM flags, runtime classpath, bootclasspath, etc is doable.
    // We'd share the same limitation if the system under test uses shutdown hooks internally, as
    // there's no way to collect coverage data on that code.
    String mainClass = getMainClass(metadataJar);
    Method main =
        Class.forName(mainClass).getMethod("main", new Class[] {java.lang.String[].class});
    main.invoke(null, new Object[] {args});
  }
}
