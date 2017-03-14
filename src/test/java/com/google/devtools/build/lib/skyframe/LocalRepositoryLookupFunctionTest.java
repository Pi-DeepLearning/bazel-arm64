// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PackageLookupValue.BuildFileName;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequentialBuildDriver;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link LocalRepositoryLookupFunction}. */
@RunWith(JUnit4.class)
public class LocalRepositoryLookupFunctionTest extends FoundationTestCase {
  private AtomicReference<ImmutableSet<PackageIdentifier>> deletedPackages;
  private MemoizingEvaluator evaluator;
  private SequentialBuildDriver driver;
  private RecordingDifferencer differencer;

  @Before
  public final void setUp() throws Exception {
    AnalysisMock analysisMock = AnalysisMock.get();
    AtomicReference<PathPackageLocator> pkgLocator =
        new AtomicReference<>(new PathPackageLocator(outputBase, ImmutableList.of(rootDirectory)));
    deletedPackages = new AtomicReference<>(ImmutableSet.<PackageIdentifier>of());
    BlazeDirectories directories =
        new BlazeDirectories(
            rootDirectory, outputBase, rootDirectory, analysisMock.getProductName());
    ExternalFilesHelper externalFilesHelper = new ExternalFilesHelper(
        pkgLocator, ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS, directories);

    Map<SkyFunctionName, SkyFunction> skyFunctions = new HashMap<>();
    skyFunctions.put(
        SkyFunctions.PACKAGE_LOOKUP,
        new PackageLookupFunction(
            deletedPackages,
            CrossRepositoryLabelViolationStrategy.ERROR,
            ImmutableList.of(BuildFileName.BUILD_DOT_BAZEL, BuildFileName.BUILD)));
    skyFunctions.put(
        SkyFunctions.FILE_STATE,
        new FileStateFunction(
            new AtomicReference<TimestampGranularityMonitor>(), externalFilesHelper));
    skyFunctions.put(SkyFunctions.FILE, new FileFunction(pkgLocator));
    skyFunctions.put(SkyFunctions.DIRECTORY_LISTING, new DirectoryListingFunction());
    skyFunctions.put(
        SkyFunctions.DIRECTORY_LISTING_STATE,
        new DirectoryListingStateFunction(externalFilesHelper));
    RuleClassProvider ruleClassProvider = analysisMock.createRuleClassProvider();
    skyFunctions.put(SkyFunctions.WORKSPACE_AST, new WorkspaceASTFunction(ruleClassProvider));
    skyFunctions.put(
        SkyFunctions.WORKSPACE_FILE,
        new WorkspaceFileFunction(
            ruleClassProvider,
            analysisMock
                .getPackageFactoryForTesting()
                .create(
                    ruleClassProvider,
                    new PackageFactory.EmptyEnvironmentExtension(),
                    scratch.getFileSystem()),
            directories));
    skyFunctions.put(SkyFunctions.EXTERNAL_PACKAGE, new ExternalPackageFunction());
    skyFunctions.put(SkyFunctions.LOCAL_REPOSITORY_LOOKUP, new LocalRepositoryLookupFunction());
    skyFunctions.put(
        SkyFunctions.FILE_SYMLINK_CYCLE_UNIQUENESS, new FileSymlinkCycleUniquenessFunction());

    differencer = new RecordingDifferencer();
    evaluator = new InMemoryMemoizingEvaluator(skyFunctions, differencer);
    driver = new SequentialBuildDriver(evaluator);
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());
  }

  private SkyKey createKey(RootedPath directory) {
    return LocalRepositoryLookupValue.key(directory);
  }

  private LocalRepositoryLookupValue lookupDirectory(RootedPath directory)
      throws InterruptedException {
    SkyKey key = createKey(directory);
    return lookupDirectory(key).get(key);
  }

  private EvaluationResult<LocalRepositoryLookupValue> lookupDirectory(SkyKey directoryKey)
      throws InterruptedException {
    return driver.<LocalRepositoryLookupValue>evaluate(
        ImmutableList.of(directoryKey),
        false,
        SkyframeExecutor.DEFAULT_THREAD_COUNT,
        NullEventHandler.INSTANCE);
  }

  @Test
  public void testNoPath() throws Exception {
    LocalRepositoryLookupValue repositoryLookupValue =
        lookupDirectory(RootedPath.toRootedPath(rootDirectory, PathFragment.EMPTY_FRAGMENT));
    assertThat(repositoryLookupValue).isNotNull();
    assertThat(repositoryLookupValue.getRepository()).isEqualTo(RepositoryName.MAIN);
  }

  @Test
  public void testActualPackage() throws Exception {
    scratch.file("some/path/BUILD");

    LocalRepositoryLookupValue repositoryLookupValue =
        lookupDirectory(RootedPath.toRootedPath(rootDirectory, new PathFragment("some/path")));
    assertThat(repositoryLookupValue).isNotNull();
    assertThat(repositoryLookupValue.getRepository()).isEqualTo(RepositoryName.MAIN);
  }

  @Test
  public void testLocalRepository() throws Exception {
    scratch.overwriteFile("WORKSPACE", "local_repository(name='local', path='local/repo')");
    scratch.file("local/repo/WORKSPACE");
    scratch.file("local/repo/BUILD");

    LocalRepositoryLookupValue repositoryLookupValue =
        lookupDirectory(RootedPath.toRootedPath(rootDirectory, new PathFragment("local/repo")));
    assertThat(repositoryLookupValue).isNotNull();
    assertThat(repositoryLookupValue.getRepository().getName()).isEqualTo("@local");
  }

  @Test
  public void testLocalRepositorySubPackage() throws Exception {
    scratch.overwriteFile("WORKSPACE", "local_repository(name='local', path='local/repo')");
    scratch.file("local/repo/WORKSPACE");
    scratch.file("local/repo/BUILD");
    scratch.file("local/repo/sub/package/BUILD");

    LocalRepositoryLookupValue repositoryLookupValue =
        lookupDirectory(
            RootedPath.toRootedPath(rootDirectory, new PathFragment("local/repo/sub/package")));
    assertThat(repositoryLookupValue).isNotNull();
    assertThat(repositoryLookupValue.getRepository().getName()).isEqualTo("@local");
  }

  @Test
  public void testWorkspaceButNoLocalRepository() throws Exception {
    scratch.overwriteFile("WORKSPACE", "");
    scratch.file("local/repo/WORKSPACE");
    scratch.file("local/repo/BUILD");

    LocalRepositoryLookupValue repositoryLookupValue =
        lookupDirectory(RootedPath.toRootedPath(rootDirectory, new PathFragment("local/repo")));
    assertThat(repositoryLookupValue).isNotNull();
    assertThat(repositoryLookupValue.getRepository()).isEqualTo(RepositoryName.MAIN);
  }

  @Test
  public void testLocalRepository_LocalWorkspace_SymlinkCycle() throws Exception {
    scratch.overwriteFile("WORKSPACE", "local_repository(name='local', path='local/repo')");
    Path localRepoWorkspace = scratch.resolve("local/repo/WORKSPACE");
    Path localRepoWorkspaceLink = scratch.resolve("local/repo/WORKSPACE.link");
    FileSystemUtils.createDirectoryAndParents(localRepoWorkspace.getParentDirectory());
    FileSystemUtils.createDirectoryAndParents(localRepoWorkspaceLink.getParentDirectory());
    localRepoWorkspace.createSymbolicLink(localRepoWorkspaceLink);
    localRepoWorkspaceLink.createSymbolicLink(localRepoWorkspace);
    scratch.file("local/repo/BUILD");

    SkyKey localRepositoryKey =
        createKey(RootedPath.toRootedPath(rootDirectory, new PathFragment("local/repo")));
    EvaluationResult<LocalRepositoryLookupValue> result = lookupDirectory(localRepositoryKey);

    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(localRepositoryKey)
        .hasExceptionThat()
        .hasMessage(
            "FileSymlinkException while checking if there is a WORKSPACE file in "
                + "/workspace/local/repo");
  }

  @Test
  public void testLocalRepository_MainWorkspace_NotFound() throws Exception {
    // Do not add a local_repository to WORKSPACE.
    scratch.overwriteFile("WORKSPACE", "");
    scratch.deleteFile("WORKSPACE");
    scratch.file("local/repo/WORKSPACE");
    scratch.file("local/repo/BUILD");

    LocalRepositoryLookupValue repositoryLookupValue =
        lookupDirectory(RootedPath.toRootedPath(rootDirectory, new PathFragment("local/repo")));
    assertThat(repositoryLookupValue).isNotNull();
    // In this case, the repository should be MAIN as we can't find any local_repository rules.
    assertThat(repositoryLookupValue.getRepository()).isEqualTo(RepositoryName.MAIN);
  }

  // TODO(katre): Add tests for the following exceptions
  // While reading dir/WORKSPACE:
  // - IOException
  // - FileSymlinkException
  // - InconsistentFilesystemException
  // While loading //external
  // - BuildFileNotFoundException
  // - InconsistentFilesystemException
  // While reading //external:WORKSPACE
  // - PackageFunctionException
  // - NameConflictException
  // - WorkspaceFileException
}
