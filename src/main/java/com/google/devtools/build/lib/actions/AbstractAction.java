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

package com.google.devtools.build.lib.actions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.cache.MetadataHandler;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.CollectionUtils;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.AspectDescriptor;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.SkyFunction;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import javax.annotation.Nullable;

/**
 * Abstract implementation of Action which implements basic functionality: the inputs, outputs, and
 * toString method. Both input and output sets are immutable. Subclasses must be generally
 * immutable - see the documentation on {@link Action}.
 */
@Immutable @ThreadSafe
@SkylarkModule(
    name = "Action",
    category = SkylarkModuleCategory.BUILTIN,
    doc = "An action created on a <a href=\"ctx.html\">ctx</a> object. You can retrieve these "
        + "using the <a href=\"globals.html#Actions\">Actions</a> provider. Some fields are only "
        + "applicable for certain kinds of actions. Fields that are inapplicable are set to "
        + "<code>None</code>."
)
public abstract class AbstractAction implements Action, SkylarkValue {

  /**
   * An arbitrary default resource set. Currently 250MB of memory, 50% CPU and 0% of total I/O.
   */
  public static final ResourceSet DEFAULT_RESOURCE_SET =
      ResourceSet.createWithRamCpuIo(250, 0.5, 0);

  /**
   * The owner/inputs/outputs attributes below should never be directly accessed even within
   * AbstractAction itself. The appropriate getter methods should be used instead. This has to be
   * done due to the fact that the getter methods can be overridden in subclasses.
   */
  private final ActionOwner owner;

  /**
   * Tools are a subset of inputs and used by the WorkerSpawnStrategy to determine whether a
   * compiler has changed since the last time it was used. This should include all artifacts that
   * the tool does not dynamically reload / check on each unit of work - e.g. its own binary, the
   * JDK for Java binaries, shared libraries, ... but not a configuration file, if it reloads that
   * when it has changed.
   *
   * <p>If the "tools" set does not contain exactly the right set of artifacts, the following can
   * happen: If an artifact that should be included is missing, the tool might not be restarted when
   * it should, and builds can become incorrect (example: The compiler binary is not part of this
   * set, then the compiler gets upgraded, but the worker strategy still reuses the old version).
   * If an artifact that should *not* be included is accidentally part of this set, the worker
   * process will be restarted more often that is necessary - e.g. if a file that is unique to each
   * unit of work, e.g. the source code that a compiler should compile for a compile action, is
   * part of this set, then the worker will never be reused and will be restarted for each unit of
   * work.
   */
  private final Iterable<Artifact> tools;

  // The variable inputs is non-final only so that actions that discover their inputs can modify it.
  private Iterable<Artifact> inputs;
  private final Iterable<String> clientEnvironmentVariables;
  private final RunfilesSupplier runfilesSupplier;
  private final ImmutableSet<Artifact> outputs;

  private String cachedKey;

  /**
   * Construct an abstract action with the specified inputs and outputs;
   */
  protected AbstractAction(ActionOwner owner,
                           Iterable<Artifact> inputs,
                           Iterable<Artifact> outputs) {
    this(owner, ImmutableList.<Artifact>of(), inputs, EmptyRunfilesSupplier.INSTANCE, outputs);
  }

  /**
   * Construct an abstract action with the specified tools, inputs and outputs;
   */
  protected AbstractAction(
      ActionOwner owner,
      Iterable<Artifact> tools,
      Iterable<Artifact> inputs,
      Iterable<Artifact> outputs) {
    this(owner, tools, inputs, EmptyRunfilesSupplier.INSTANCE, outputs);
  }

  protected AbstractAction(
      ActionOwner owner,
      Iterable<Artifact> inputs,
      RunfilesSupplier runfilesSupplier,
      Iterable<Artifact> outputs) {
    this(owner, ImmutableList.<Artifact>of(), inputs, runfilesSupplier, outputs);
  }

  protected AbstractAction(
      ActionOwner owner,
      Iterable<Artifact> tools,
      Iterable<Artifact> inputs,
      RunfilesSupplier runfilesSupplier,
      Iterable<Artifact> outputs) {
    this(owner, tools, inputs, ImmutableList.<String>of(), runfilesSupplier, outputs);
  }

  protected AbstractAction(
      ActionOwner owner,
      Iterable<Artifact> tools,
      Iterable<Artifact> inputs,
      Iterable<String> clientEnvironmentVariables,
      RunfilesSupplier runfilesSupplier,
      Iterable<Artifact> outputs) {
    Preconditions.checkNotNull(owner);
    // TODO(bazel-team): Use RuleContext.actionOwner here instead
    this.owner = owner;
    this.tools = CollectionUtils.makeImmutable(tools);
    this.inputs = CollectionUtils.makeImmutable(inputs);
    this.clientEnvironmentVariables = clientEnvironmentVariables;
    this.outputs = ImmutableSet.copyOf(outputs);
    this.runfilesSupplier = Preconditions.checkNotNull(runfilesSupplier,
        "runfilesSupplier may not be null");
    Preconditions.checkArgument(!this.outputs.isEmpty(), "action outputs may not be empty");
  }

  @Override
  public final ActionOwner getOwner() {
    return owner;
  }

  @Override
  public boolean inputsKnown() {
    return true;
  }

  @Override
  public boolean discoversInputs() {
    return false;
  }

  @Override
  public Iterable<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    throw new IllegalStateException("discoverInputs cannot be called for " + this.prettyPrint()
        + " since it does not discover inputs");
  }

  @Override
  public Iterable<Artifact> discoverInputsStage2(SkyFunction.Environment env)
      throws ActionExecutionException, InterruptedException {
    return null;
  }

  @Nullable
  @Override
  public Iterable<Artifact> getInputsWhenSkippingInputDiscovery() {
    return null;
  }

  @Nullable
  @Override
  public Iterable<Artifact> resolveInputsFromCache(
      ArtifactResolver artifactResolver,
      PackageRootResolver resolver,
      Collection<PathFragment> inputPaths)
      throws PackageRootResolutionException, InterruptedException {
    throw new IllegalStateException(
        "Method must be overridden for actions that may have unknown inputs.");
  }

  @Override
  public void updateInputs(Iterable<Artifact> inputs) {
    throw new IllegalStateException(
        "Method must be overridden for actions that may have unknown inputs.");
  }

  @Override
  public Iterable<Artifact> getTools() {
    return tools;
  }

  /**
   * Should only be overridden by actions that need to optionally insert inputs. Actions that
   * discover their inputs should use {@link #setInputs} to set the new iterable of inputs when they
   * know it.
   */
  @Override
  public Iterable<Artifact> getInputs() {
    return inputs;
  }

  @Override
  public Iterable<String> getClientEnvironmentVariables() {
    return clientEnvironmentVariables;
  }

  @Override
  public RunfilesSupplier getRunfilesSupplier() {
    return runfilesSupplier;
  }

  /**
   * Set the inputs of the action. May only be used by an action that {@link #discoversInputs()}.
   * The iterable passed in is automatically made immutable.
   */
  protected void setInputs(Iterable<Artifact> inputs) {
    Preconditions.checkState(discoversInputs(), this);
    this.inputs = CollectionUtils.makeImmutable(inputs);
  }

  @Override
  public ImmutableSet<Artifact> getOutputs() {
    return outputs;
  }

  @Override
  public Artifact getPrimaryInput() {
    // The default behavior is to return the first input artifact.
    // Call through the method, not the field, because it may be overridden.
    return Iterables.getFirst(getInputs(), null);
  }

  @Override
  public Artifact getPrimaryOutput() {
    // Default behavior is to return the first output artifact.
    // Use the method rather than field in case of overriding in subclasses.
    return Iterables.getFirst(getOutputs(), null);
  }

  @Override
  public Iterable<Artifact> getMandatoryInputs() {
    return getInputs();
  }

  @Override
  public String toString() {
    return prettyPrint() + " (" + getMnemonic() + "[" + ImmutableList.copyOf(getInputs())
        + (inputsKnown() ? " -> " : ", unknown inputs -> ")
        + getOutputs() + "]" + ")";
  }

  @Override
  public abstract String getMnemonic();

  /**
   * See the javadoc for {@link com.google.devtools.build.lib.actions.Action} and
   * {@link com.google.devtools.build.lib.actions.ActionExecutionMetadata#getKey()} for the contract
   * for {@link #computeKey()}.
   */
  protected abstract String computeKey();

  @Override
  public final synchronized String getKey() {
    if (cachedKey == null) {
      cachedKey = computeKey();
    }
    return cachedKey;
  }

  @Override
  public String describeKey() {
    return null;
  }

  @Override
  public boolean executeUnconditionally() {
    return false;
  }

  @Override
  public boolean isVolatile() {
    return false;
  }

  @Override
  public boolean showsOutputUnconditionally() {
    return false;
  }

  @Override
  public final String getProgressMessage() {
    String message = getRawProgressMessage();
    if (message == null) {
      return null;
    }
    String additionalInfo = getOwner().getAdditionalProgressInfo();
    return additionalInfo == null ? message : message + " [" + additionalInfo + "]";
  }

  /**
   * Returns a progress message string that is specific for this action. This is
   * then annotated with additional information, currently the string '[for host]'
   * for actions in the host configurations.
   *
   * <p>A return value of null indicates no message should be reported.
   */
  protected String getRawProgressMessage() {
    // A cheesy default implementation.  Subclasses are invited to do something
    // more meaningful.
    return defaultProgressMessage();
  }

  private String defaultProgressMessage() {
    return getMnemonic() + " " + getPrimaryOutput().prettyPrint();
  }

  @Override
  public String prettyPrint() {
    return "action '" + describe() + "'";
  }

  @Override
  public boolean isImmutable() {
    return false;
  }

  @Override
  public void write(Appendable buffer, char quotationMark) {
    Printer.append(buffer, prettyPrint()); // TODO(bazel-team): implement a readable representation
  }

  /**
   * Deletes all of the action's output files, if they exist. If any of the
   * Artifacts refers to a directory recursively removes the contents of the
   * directory.
   *
   * @param execRoot the exec root in which this action is executed
   */
  protected void deleteOutputs(Path execRoot) throws IOException {
    for (Artifact output : getOutputs()) {
      deleteOutput(output);
    }
  }

  /**
   * Helper method to remove an Artifact. If the Artifact refers to a directory
   * recursively removes the contents of the directory.
   */
  protected void deleteOutput(Artifact output) throws IOException {
    Path path = output.getPath();
    try {
      // Optimize for the common case: output artifacts are files.
      path.delete();
    } catch (IOException e) {
      // Handle a couple of scenarios where the output can still be deleted, but make sure we're not
      // deleting random files on the filesystem.
      if (output.getRoot() == null) {
        throw e;
      }
      String outputRootDir = output.getRoot().getPath().getPathString();
      if (!path.getPathString().startsWith(outputRootDir)) {
        throw e;
      }

      Path parentDir = path.getParentDirectory();
      if (!parentDir.isWritable() && parentDir.getPathString().startsWith(outputRootDir)) {
        // Retry deleting after making the parent writable.
        parentDir.setWritable(true);
        deleteOutput(output);
      } else if (path.isDirectory(Symlinks.NOFOLLOW)) {
        FileSystemUtils.deleteTree(path);
      } else {
        throw e;
      }
    }
  }

  /**
   * If the action might read directories as inputs in a way that is unsound wrt dependency
   * checking, this method must be called.
   */
  protected void checkInputsForDirectories(EventHandler eventHandler,
                                           MetadataHandler metadataHandler) {
    // Report "directory dependency checking" warning only for non-generated directories (generated
    // ones will be reported earlier).
    for (Artifact input : getMandatoryInputs()) {
      // Assume that if the file did not exist, we would not have gotten here.
      if (input.isSourceArtifact() && !metadataHandler.isRegularFile(input)) {
        eventHandler.handle(Event.warn(getOwner().getLocation(), "input '"
            + input.prettyPrint() + "' to " + getOwner().getLabel()
            + " is a directory; dependency checking of directories is unsound"));
      }
    }
  }

  @Override
  public MiddlemanType getActionType() {
    return MiddlemanType.NORMAL;
  }

  /**
   * If the action might create directories as outputs this method must be called.
   */
  protected void checkOutputsForDirectories(EventHandler eventHandler) {
    for (Artifact output : getOutputs()) {
      Path path = output.getPath();
      String ownerString = Label.print(getOwner().getLabel());
      if (path.isDirectory()) {
        eventHandler.handle(
            Event.warn(
                getOwner().getLocation(),
                "output '" + output.prettyPrint() + "' of " + ownerString
                    + " is a directory; dependency checking of directories is unsound")
                .withTag(ownerString));
      }
    }
  }

  @Override
  public void prepare(Path execRoot) throws IOException {
    deleteOutputs(execRoot);
  }

  @Override
  public String describe() {
    String progressMessage = getProgressMessage();
    return progressMessage != null ? progressMessage : defaultProgressMessage();
  }

  @Override
  public boolean shouldReportPathPrefixConflict(ActionAnalysisMetadata action) {
    return this != action;
  }

  @Override
  public boolean extraActionCanAttach() {
    return true;
  }

  @Override
  public ExtraActionInfo.Builder getExtraActionInfo() {
    ActionOwner owner = getOwner();
    ExtraActionInfo.Builder result =
        ExtraActionInfo.newBuilder()
            .setOwner(owner.getLabel().toString())
            .setId(getKey())
            .setMnemonic(getMnemonic());
    Iterable<AspectDescriptor> aspectDescriptors = owner.getAspectDescriptors();
    AspectDescriptor lastAspect = null;

    for (AspectDescriptor aspectDescriptor : aspectDescriptors) {
      ExtraActionInfo.AspectDescriptor.Builder builder =
          ExtraActionInfo.AspectDescriptor.newBuilder()
            .setAspectName(aspectDescriptor.getAspectClass().getName());
      for (Entry<String, Collection<String>> entry :
          aspectDescriptor.getParameters().getAttributes().asMap().entrySet()) {
          builder.putAspectParameters(
            entry.getKey(),
            ExtraActionInfo.AspectDescriptor.StringList.newBuilder()
                .addAllValue(entry.getValue())
                .build()
          );
      }
      lastAspect = aspectDescriptor;
    }
    if (lastAspect != null) {
      result.setAspectName(lastAspect.getAspectClass().getName());

      for (Map.Entry<String, Collection<String>> entry :
          lastAspect.getParameters().getAttributes().asMap().entrySet()) {
        result.putAspectParameters(
            entry.getKey(),
            ExtraActionInfo.StringList.newBuilder().addAllValue(entry.getValue()).build());
      }
    }
    return result;
  }

  @Override
  public ImmutableSet<Artifact> getMandatoryOutputs() {
    return ImmutableSet.of();
  }

  /**
   * Returns input files that need to be present to allow extra_action rules to shadow this action
   * correctly when run remotely. This is at least the normal inputs of the action, but may include
   * other files as well. For example C(++) compilation may perform include file header scanning.
   * This needs to be mirrored by the extra_action rule. Called by
   * {@link com.google.devtools.build.lib.rules.extra.ExtraAction} at execution time.
   *
   * <p>As this method is called from the ExtraAction, make sure it is ok to call
   * this method from a different thread than the one this action is executed on.
   *
   * @param actionExecutionContext Services in the scope of the action, like the Out/Err streams.
   * @throws ActionExecutionException only when code called from this method
   *     throws that exception.
   * @throws InterruptedException if interrupted
   */
  public Iterable<Artifact> getInputFilesForExtraAction(
      ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    return getInputs();
  }

  @SkylarkCallable(
      name = "inputs",
      doc = "A set of the input files of this action.",
      structField = true)
  public SkylarkNestedSet getSkylarkInputs() {
    return SkylarkNestedSet.of(Artifact.class, NestedSetBuilder.wrap(
        Order.STABLE_ORDER, getInputs()));
  }

  @SkylarkCallable(
      name = "outputs",
      doc = "A set of the output files of this action.",
      structField = true)
  public SkylarkNestedSet getSkylarkOutputs() {
    return SkylarkNestedSet.of(Artifact.class, NestedSetBuilder.wrap(
        Order.STABLE_ORDER, getOutputs()));
  }

  @SkylarkCallable(
      name = "argv",
      doc = "For actions created by <a href=\"ctx.html#action\">ctx.action()</a>, an immutable "
          + "list of the arguments for the command line to be executed. Note that when using the "
          + "shell (i.e., when <a href=\"ctx.html#action.executable\">executable</a> is not set), "
          + "the first two arguments will be the shell path and <code>\"-c\"</code>.",
      structField = true,
      allowReturnNones = true)
  public SkylarkList<String> getSkylarkArgv() {
    return null;
  }

  @SkylarkCallable(
      name = "content",
      doc = "For actions created by <a href=\"ctx.html#file_action\">ctx.file_action()</a> or "
          + "<a href=\"ctx.html#template_action\">ctx.template_action()</a>, the contents of the "
          + "file to be written.",
      structField = true,
      allowReturnNones = true)
  public String getSkylarkContent() throws IOException {
    return null;
  }

  @SkylarkCallable(
      name = "substitutions",
      doc = "For actions created by <a href=\"ctx.html#template_action\">"
          + "ctx.template_action()</a>, an immutable dict holding the substitution mapping.",
      structField = true,
      allowReturnNones = true)
  public SkylarkDict<String, String> getSkylarkSubstitutions() {
    return null;
  }
}
