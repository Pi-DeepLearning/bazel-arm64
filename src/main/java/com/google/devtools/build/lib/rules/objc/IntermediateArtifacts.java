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

package com.google.devtools.build.lib.rules.objc;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.cpp.CppCompileAction.DotdFile;
import com.google.devtools.build.lib.rules.cpp.CppModuleMap;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;

/**
 * Factory class for generating artifacts which are used as intermediate output.
 */
// TODO(bazel-team): This should really be named DerivedArtifacts as it contains methods for
// final as well as intermediate artifacts.
public final class IntermediateArtifacts {
  static final String LINKMAP_SUFFIX = ".linkmap";

  /**
   * Extension used for the zip archive containing dsym files and their associated plist. Note that
   * the archive's path less this extension corresponds to the expected directory for dsym files
   * relative to their binary.
   */
  static final String DSYM_ZIP_EXTENSION = ".temp.zip";

  private final RuleContext ruleContext;
  private final BuildConfiguration buildConfiguration;
  private final String archiveFileNameSuffix;
  private final String outputPrefix;

  IntermediateArtifacts(RuleContext ruleContext, String archiveFileNameSuffix,
      String outputPrefix) {
    this(ruleContext, archiveFileNameSuffix, outputPrefix, ruleContext.getConfiguration());
  }

  IntermediateArtifacts(RuleContext ruleContext, String archiveFileNameSuffix) {
    this(ruleContext, archiveFileNameSuffix, "", ruleContext.getConfiguration());
  }
 
  IntermediateArtifacts(RuleContext ruleContext, String archiveFileNameSuffix,
      String outputPrefix, BuildConfiguration buildConfiguration) {
    this.ruleContext = ruleContext;
    this.buildConfiguration = buildConfiguration;
    this.archiveFileNameSuffix = Preconditions.checkNotNull(archiveFileNameSuffix);
    this.outputPrefix = Preconditions.checkNotNull(outputPrefix);
  }

  /**
   * Returns a derived artifact in the bin directory obtained by appending some extension to the
   * main label name; the result artifact is placed in a unique "entitlements" directory.
   * For example, if this artifact is for a target Foo with extension ".extension", the result
   * artifact will be located at {target_base_path}/entitlements/Foo.extension.
   */
  public Artifact appendExtensionForEntitlementArtifact(String extension) {
    PathFragment entitlementsDirectory = ruleContext.getUniqueDirectory("entitlements");
    Artifact artifact =
        ruleContext.getDerivedArtifact(
            entitlementsDirectory.replaceName(
                addOutputPrefix(entitlementsDirectory.getBaseName(), extension)),
            buildConfiguration.getBinDirectory(ruleContext.getRule().getRepository()));
    return artifact;
  }

  /**
   * Returns the location of this target's generated entitlements file.
   */
  public Artifact entitlements() {
    return appendExtensionForEntitlementArtifact(".entitlements");
  }

  /**
   * Returns the location of this target's extension plist which contains entries required by all
   * watch extensions (for final merging into the bundle plist).
   */
  public Artifact watchExtensionAutomaticPlist() {
    return ruleContext.getRelatedArtifact(
        ruleContext.getUniqueDirectory("plists"), "-automatic-watchExtensionInfo.plist");
  }

  /**
   * Returns a derived artifact in the bin directory obtained by appending some extension to the end
   * of the given {@link PathFragment}.
   */
  private Artifact appendExtension(PathFragment original, String extension) {
    return scopedArtifact(FileSystemUtils.appendExtension(original,
        addOutputPrefix("", extension)));
  }

  /**
   * Returns a derived artifact in the bin directory obtained by appending some extension to the end
   * of the {@link PathFragment} corresponding to the owner {@link Label}.
   */
  private Artifact appendExtension(String extension) {
    PathFragment name = new PathFragment(ruleContext.getLabel().getName());
    return scopedArtifact(name.replaceName(addOutputPrefix(name.getBaseName(), extension)));
  }

  /**
   * A dummy .c file to be included in xcode projects. This is needed if the target does not have
   * any source files but Xcode requires one.
   */
  public Artifact dummySource() {
    return scopedArtifact(
        ruleContext.getPrerequisiteArtifact("$dummy_source", Mode.TARGET).getRootRelativePath());
  }

  /**
   * Returns a derived artifact in the genfiles directory obtained by appending some extension to
   * the end of the {@link PathFragment} corresponding to the owner {@link Label}.
   */
  private Artifact appendExtensionInGenfiles(String extension) {
    PathFragment name = new PathFragment(ruleContext.getLabel().getName());
    return scopedArtifact(
        name.replaceName(addOutputPrefix(name.getBaseName(), extension)), /* inGenfiles = */ true);
  }

  /**
   * The output of using {@code actoolzip} to run {@code actool} for a given bundle which is
   * merged under the {@code .app} or {@code .bundle} directory root.
   */
  public Artifact actoolzipOutput() {
    return appendExtension(".actool.zip");
  }

  /**
   * Output of the partial infoplist generated by {@code actool} when given the
   * {@code --output-partial-info-plist [path]} flag.
   */
  public Artifact actoolPartialInfoplist() {
    return appendExtension(".actool-PartialInfo.plist");
  }

  /**
   * The Info.plist file for a bundle which is comprised of more than one originating plist file.
   * This is not needed for a bundle which has no source Info.plist files, or only one Info.plist
   * file, since no merging occurs in that case.
   */
  public Artifact mergedInfoplist() {
    return appendExtension("-MergedInfo.plist");
  }

  /**
   * The .objlist file, which contains a list of paths of object files to archive and is read by
   * clang (via -filelist flag) in the link action (for binary creation).
   */
  public Artifact linkerObjList() {
    return appendExtension("-linker.objlist");
  }

  /**
   * The .objlist file, which contains a list of paths of object files to archive  and is read by
   * libtool (via -filelist flag) in the archive action.
   */
  public Artifact archiveObjList() {
    return appendExtension("-archive.objlist");
  }

  /**
   * The artifact which is the binary (or library) which is comprised of one or more .a files linked
   * together. Compared to the artifact returned by {@link #unstrippedSingleArchitectureBinary},
   * this artifact is stripped of symbol table when --compilation_mode=opt and
   * --objc_enable_binary_stripping are specified.
   */
  public Artifact strippedSingleArchitectureBinary() {
    return appendExtension("_bin");
  }

  /**
   * The artifact which is the fully-linked static library comprised of statically linking compiled
   * sources and dependencies together.
   */
  public Artifact strippedSingleArchitectureLibrary() {
    return appendExtension("-fl.a");
  }

  /**
   * The artifact which is the binary (or library) which is comprised of one or more .a files linked
   * together. It also contains full debug symbol information, compared to the artifact returned
   * by {@link #strippedSingleArchitectureBinary}. This artifact will serve as input for the symbol
   * strip action and is only created when --compilation_mode=opt and
   * --objc_enable_binary_stripping are specified.
   */
  public Artifact unstrippedSingleArchitectureBinary() {
    return appendExtension("_bin_unstripped");
  }

  /**
   * Lipo binary generated by combining one or more linked binaries. This binary is the one included
   * in generated bundles and invoked as entry point to the application.
   */
  public Artifact combinedArchitectureBinary() {
    return appendExtension("_lipobin");
  }

  /**
   * Lipo archive generated by combining one or more linked archives.
   */
  public Artifact combinedArchitectureArchive() {
    return appendExtension("_lipo.a");
  }

  /**
   * Lipo'ed dynamic library generated by combining one or more single-architecure linked dynamic
   * libraries.
   */
  public Artifact combinedArchitectureDylib() {
    return appendExtension("_lipo.dylib");
  }

  private Artifact scopedArtifact(PathFragment scopeRelative, boolean inGenfiles) {
    Root root =
        inGenfiles
            ? buildConfiguration.getGenfilesDirectory(ruleContext.getRule().getRepository())
            : buildConfiguration.getBinDirectory(ruleContext.getRule().getRepository());

    // The path of this artifact will be RULE_PACKAGE/SCOPERELATIVE
    return ruleContext.getPackageRelativeArtifact(scopeRelative, root);
  }

  private Artifact scopedArtifact(PathFragment scopeRelative) {
    return scopedArtifact(scopeRelative, /* inGenfiles = */ false);
  }

  /**
   * The {@code .a} file which contains all the compiled sources for a rule.
   */
  public Artifact archive() {
    // The path will be RULE_PACKAGE/libRULEBASENAME.a
    String basename = new PathFragment(ruleContext.getLabel().getName()).getBaseName();
    return scopedArtifact(new PathFragment(String.format(
        "lib%s%s.a", basename, archiveFileNameSuffix)));
  }

  private Artifact inUniqueObjsDir(Artifact source, String extension) {
    PathFragment uniqueDir =
        new PathFragment("_objs").getRelative(ruleContext.getLabel().getName());
    PathFragment sourceFile = uniqueDir.getRelative(source.getRootRelativePath());
    PathFragment scopeRelativePath = FileSystemUtils.replaceExtension(sourceFile, extension);
    return scopedArtifact(scopeRelativePath);
  }

  /**
   * The artifact for the .o file that should be generated when compiling the {@code source}
   * artifact.
   */
  public Artifact objFile(Artifact source) {
    if (source.isTreeArtifact()) {
      PathFragment rootRelativePath = source.getRootRelativePath().replaceName("obj_files");
      return ruleContext.getTreeArtifact(rootRelativePath, ruleContext.getBinOrGenfilesDirectory());
    } else {
      return inUniqueObjsDir(source, ".o");
    }
  }

  /**
   * The artifact for the .gcno file that should be generated when compiling the {@code source}
   * artifact.
   */
  public Artifact gcnoFile(Artifact source) {
     return inUniqueObjsDir(source, ".gcno");
  }

  /**
   * Returns the artifact corresponding to the pbxproj control file, which specifies the information
   * required to generate the Xcode project file.
   */
  public Artifact pbxprojControlArtifact() {
    return appendExtension(".xcodeproj-control");
  }

  /**
   * The artifact which contains the zipped-up results of compiling the storyboard. This is merged
   * into the final bundle under the {@code .app} or {@code .bundle} directory root.
   */
  public Artifact compiledStoryboardZip(Artifact input) {
    return appendExtension("/" + BundleableFile.flatBundlePath(input.getExecPath()) + ".zip");
  }

  /**
   * Returns the artifact which is the output of building an entire xcdatamodel[d] made of artifacts
   * specified by a single rule.
   *
   * @param containerDir the containing *.xcdatamodeld or *.xcdatamodel directory
   * @return the artifact for the zipped up compilation results.
   */
  public Artifact compiledMomZipArtifact(PathFragment containerDir) {
    return appendExtension(
        "/" + FileSystemUtils.replaceExtension(containerDir, ".zip").getBaseName());
  }

  /**
   * Returns the compiled (i.e. converted to binary plist format) artifact corresponding to the
   * given {@code .strings} file.
   */
  public Artifact convertedStringsFile(Artifact originalFile) {
    return appendExtension(originalFile.getExecPath(), ".binary");
  }

  /**
   * Returns the artifact corresponding to the zipped-up compiled form of the given {@code .xib}
   * file.
   */
  public Artifact compiledXibFileZip(Artifact originalFile) {
    return appendExtension(
        "/" + FileSystemUtils.replaceExtension(originalFile.getExecPath(), ".nib.zip"));
  }

  /**
   * Returns the artifact which is the output of running swift-stdlib-tool and copying resulting
   * dylibs.
   */
  public Artifact swiftFrameworksFileZip() {
    return appendExtension(".swiftstdlib.zip");
  }

  /**
   * Same as {@link #swiftFrameworksFileZip()} but used to put Swift dylibs at a different location
   * in SwiftSupport directory at the top of the IPA.
   */
  public Artifact swiftSupportZip() {
    return appendExtension(".swiftsupport.zip");
  }

  /**
   * The temp zipped debug symbol bundle file which contains debug symbols generated by dsymutil.
   */
  public Artifact tempDsymBundleZip(DsymOutputType dsymOutputType) {
    return appendExtension(dsymOutputType.getSuffix() + DSYM_ZIP_EXTENSION);
  }

  /**
   * Debug symbol plist generated for a linked binary.
   */
  public Artifact dsymPlist(DsymOutputType dsymOutputType) {
    return appendExtension(String.format("%s/Contents/Info.plist", dsymOutputType.getSuffix()));
  }

  /**
   * Debug symbol file generated for a linked binary.
   */
  public Artifact dsymSymbol(DsymOutputType dsymOutputType) {
    return dsymSymbol(dsymOutputType, "bin");
  }

  /**
   * Debug symbol file generated for a linked binary, for a specific architecture.
   */
  public Artifact dsymSymbol(DsymOutputType dsymOutputType, String suffix) {
    return appendExtension(
        String.format(
            "%s/Contents/Resources/DWARF/%s_%s",
            dsymOutputType.getSuffix(),
            ruleContext.getLabel().getName(),
            suffix));
  }

  /**
   * Representation for a specific architecture.
   */
  private Artifact architectureRepresentation(String arch, String suffix) {
    return appendExtension(String.format("_%s%s", arch, suffix));
  }

  /**
   * Linkmap representation
   */
  public Artifact linkmap() {
    return appendExtension(LINKMAP_SUFFIX);
  }

  /**
   * Linkmap representation for a specific architecture.
   */
  public Artifact linkmap(String arch) {
    return architectureRepresentation(arch, LINKMAP_SUFFIX);
  }

  /**
   * Shell script that launches the binary.
   */
  public Artifact runnerScript() {
    return appendExtension("_runner.sh");
  }

  /** Dependency file that is generated when compiling the {@code source} artifact. */
  public DotdFile dotdFile(Artifact source) {
    return new DotdFile(inUniqueObjsDir(source, ".d"));
  }

  /**
   * {@link CppModuleMap} that provides the clang module map for this target.
   */
  public CppModuleMap moduleMap() {
    String moduleName =
        ruleContext
            .getLabel()
            .toString()
            .replace("//", "")
            .replace("@", "")
            .replace("/", "_")
            .replace(":", "_");
    // To get Swift to pick up module maps, we need to name them "module.modulemap" and have their
    // parent directory in the module map search paths.
    return new CppModuleMap(appendExtensionInGenfiles(".modulemaps/module.modulemap"), moduleName);
  }

  /**
   * Returns a static library archive with dead code/objects removed by J2ObjC dead code removal,
   * given the original unpruned static library containing J2ObjC-translated code.
   */
  public Artifact j2objcPrunedArchive(Artifact unprunedArchive) {
    PathFragment prunedSourceArtifactPath = FileSystemUtils.appendWithoutExtension(
        unprunedArchive.getRootRelativePath(), "_pruned");
    return ruleContext.getUniqueDirectoryArtifact(
        "_j2objc_pruned",
        prunedSourceArtifactPath,
        ruleContext.getBinOrGenfilesDirectory());
  }

  /**
   * Returns the location of this target's merged but not post-processed or signed IPA.
   */
  public Artifact unprocessedIpa() {
    return appendExtension(".unprocessed.ipa");
  }

  /**
   * Returns artifact name prefixed with an output prefix if specified.
   */
  private String addOutputPrefix(String baseName, String artifactName) {
    if (!outputPrefix.isEmpty()) {
      return String.format("%s-%s%s", baseName, outputPrefix, artifactName);
    }
    return String.format("%s%s", baseName, artifactName);
  }

}
