#!/bin/bash

# Copyright 2015 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Script for building bazel from scratch without bazel

PROTO_FILES=$(ls src/main/protobuf/*.proto src/main/java/com/google/devtools/build/lib/buildeventstream/proto/*.proto)
LIBRARY_JARS=$(find third_party -name '*.jar' | grep -Fv /javac-9-dev-r3297-1.jar | grep -Fv /javac7.jar | grep -Fv JavaBuilder | grep -ve third_party/grpc/grpc.*jar | tr "\n" " ")
GRPC_JAVA_VERSION=0.15.0
GRPC_LIBRARY_JARS=$(find third_party/grpc -name '*.jar' | grep -e .*${GRPC_JAVA_VERSION}.*jar | tr "\n" " ")
LIBRARY_JARS="${LIBRARY_JARS} ${GRPC_LIBRARY_JARS}"

# tl;dr - error_prone_core contains a copy of an older version of guava, so we
# need to make sure the newer version of guava always appears first on the
# classpath.
#
# Please read the comment in third_party/BUILD for more details.
LIBRARY_JARS_ARRAY=($LIBRARY_JARS)
for i in $(seq 0 $((${#LIBRARY_JARS_ARRAY[@]} - 1)))
do
  [[ "${LIBRARY_JARS_ARRAY[$i]}" =~ ^"third_party/error_prone/error_prone_core-".*\.jar$ ]] && ERROR_PRONE_INDEX=$i
  [[ "${LIBRARY_JARS_ARRAY[$i]}" =~ ^"third_party/guava/guava-".*\.jar$ ]] && GUAVA_INDEX=$i
done
[ "${ERROR_PRONE_INDEX:+present}" = "present" ] || { echo "no error prone jar"; echo "${LIBRARY_JARS_ARRAY[@]}"; exit 1; }
[ "${GUAVA_INDEX:+present}" = "present" ] || { echo "no guava jar"; exit 1; }
if [ "$ERROR_PRONE_INDEX" -lt "$GUAVA_INDEX" ]; then
  TEMP_FOR_SWAP="${LIBRARY_JARS_ARRAY[$ERROR_PRONE_INDEX]}"
  LIBRARY_JARS_ARRAY[$ERROR_PRONE_INDEX]="${LIBRARY_JARS_ARRAY[$GUAVA_INDEX]}"
  LIBRARY_JARS_ARRAY[$GUAVA_INDEX]="$TEMP_FOR_SWAP"
  LIBRARY_JARS="${LIBRARY_JARS_ARRAY[@]}"
fi

DIRS=$(echo src/{java_tools/singlejar/java/com/google/devtools/build/zip,main/java,tools/xcode-common/java/com/google/devtools/build/xcode/{common,util}} third_party/java/dd_plist/java ${OUTPUT_DIR}/src)
EXCLUDE_FILES=src/main/java/com/google/devtools/build/lib/server/GrpcServerImpl.java

mkdir -p ${OUTPUT_DIR}/classes
mkdir -p ${OUTPUT_DIR}/src

# May be passed in from outside.
ZIPOPTS="$ZIPOPTS"

unset JAVA_TOOL_OPTIONS
unset _JAVA_OPTIONS

LDFLAGS=${LDFLAGS:-""}

MSYS_DLLS=""
PATHSEP=":"

# Check that javac -version returns a upper version than $JAVA_VERSION.
get_java_version
[ ${JAVA_VERSION#*.} -le ${JAVAC_VERSION#*.} ] || \
  fail "JDK version (${JAVAC_VERSION}) is lower than ${JAVA_VERSION}, please set \$JAVA_HOME."

JAR="${JAVA_HOME}/bin/jar"

# Compiles java classes.
function java_compilation() {
  local name=$1
  local directories=$2
  local excludes=$3
  local library_jars=$4
  local output=$5

  local classpath=${library_jars// /$PATHSEP}${PATHSEP}$5
  local sourcepath=${directories// /$PATHSEP}

  tempdir
  local tmp="${NEW_TMPDIR}"
  local paramfile="${tmp}/param"
  local filelist="${tmp}/filelist"
  local excludefile="${tmp}/excludefile"
  touch $paramfile

  mkdir -p "${output}/classes"

  # Compile .java files (incl. generated ones) using javac
  log "Compiling $name code..."
  find ${directories} -name "*.java" | sort > "$filelist"
  # Quotes around $excludes intentionally omitted in the for statement so that
  # it's split on spaces
  (for i in $excludes; do
    echo $i
  done) | sort > "$excludefile"

  comm -23 "$filelist" "$excludefile" > "$paramfile"

  if [ ! -z "$BAZEL_DEBUG_JAVA_COMPILATION" ]; then
    echo "directories=${directories}" >&2
    echo "classpath=${classpath}" >&2
    echo "sourcepath=${sourcepath}" >&2
    echo "libraries=${library_jars}" >&2
    echo "output=${output}/classes" >&2
    echo "List of compiled files:" >&2
    cat "$paramfile" >&2
  fi

  run "${JAVAC}" -classpath "${classpath}" -sourcepath "${sourcepath}" \
      -d "${output}/classes" -source "$JAVA_VERSION" -target "$JAVA_VERSION" \
      -encoding UTF-8 "@${paramfile}"

  log "Extracting helper classes for $name..."
  for f in ${library_jars} ; do
    run unzip -qn ${f} -d "${output}/classes"
  done
}

# Create the deploy JAR
function create_deploy_jar() {
  local name=$1
  local mainClass=$2
  local output=$3
  shift 3
  local packages=""
  for i in $output/classes/*; do
    local package=$(basename $i)
    if [[ "$package" != "META-INF" ]]; then
      packages="$packages -C $output/classes $package"
    fi
  done

  log "Creating $name.jar..."
  echo "Main-Class: $mainClass" > $output/MANIFEST.MF
  run "$JAR" cmf $output/MANIFEST.MF $output/$name.jar $packages "$@"
}

HOW_TO_BOOTSTRAP='

--------------------------------------------------------------------------------
NOTE: This failure is likely occuring if you are trying to bootstrap bazel from
a developer checkout. Those checkouts do not include the generated output of
the protoc compiler (as we prefer not to version generated files).

* To build a developer version of bazel, do

    bazel build //src:bazel

* To bootstrap your first bazel binary, please download a dist archive from our
  release page at https://github.com/bazelbuild/bazel/releases and run
  compile.sh on the unpacked archive.

The full install instructions to install a release version of bazel can be found
at https://bazel.build/versions/master/docs/install.html#compiling-from-source
For a rationale, why the bootstrap process is organized in this way, see
https://bazel.build/designs/2016/10/11/distribution-artifact.html
--------------------------------------------------------------------------------

'

if [ -z "${BAZEL_SKIP_JAVA_COMPILATION}" ]; then

    if [ -d derived/src/java ]
    then
        log "Using pre-generated java proto files"
        mkdir -p "${OUTPUT_DIR}/src"
        cp -r derived/src/java/* "${OUTPUT_DIR}/src"
    else

        [ -n "${PROTOC}" ] \
            || fail "Must specify PROTOC if not bootstrapping from the distribution artifact${HOW_TO_BOOTSTRAP}"

        [ -n "${GRPC_JAVA_PLUGIN}" ] \
            || fail "Must specify GRPC_JAVA_PLUGIN if not bootstrapping from the distribution artifact${HOW_TO_BOOTSTRAP}"

        [[ -x "${PROTOC-}" ]] \
            || fail "Protobuf compiler not found in ${PROTOC-}"

        [[ -x "${GRPC_JAVA_PLUGIN-}" ]] \
            || fail "gRPC Java plugin not found in ${GRPC_JAVA_PLUGIN-}"

        log "Compiling Java stubs for protocol buffers..."
        for f in $PROTO_FILES ; do
            run "${PROTOC}" -Isrc/main/protobuf/ \
                -Isrc/main/java/com/google/devtools/build/lib/buildeventstream/proto/ \
                --java_out=${OUTPUT_DIR}/src \
                --plugin=protoc-gen-grpc="${GRPC_JAVA_PLUGIN-}" \
                --grpc_out=${OUTPUT_DIR}/src "$f"
        done
    fi

  java_compilation "Bazel Java" "$DIRS" "$EXCLUDE_FILES" "$LIBRARY_JARS" "${OUTPUT_DIR}"

  # help files: all non java and BUILD files in src/main/java.
  for i in $(find src/main/java -type f -a \! -name '*.java' -a \! -name 'BUILD' | sed 's|src/main/java/||'); do
    mkdir -p $(dirname ${OUTPUT_DIR}/classes/$i)
    cp src/main/java/$i ${OUTPUT_DIR}/classes/$i
  done

  # Overwrite tools.WORKSPACE, this is only for the bootstrap binary
  chmod u+w "${OUTPUT_DIR}/classes/com/google/devtools/build/lib/bazel/rules/tools.WORKSPACE"
  cat <<EOF >${OUTPUT_DIR}/classes/com/google/devtools/build/lib/bazel/rules/tools.WORKSPACE
local_repository(name = 'bazel_tools', path = __workspace_dir__)
bind(name = "cc_toolchain", actual = "@bazel_tools//tools/cpp:default-toolchain")
EOF

  create_deploy_jar "libblaze" "com.google.devtools.build.lib.bazel.BazelMain" \
      ${OUTPUT_DIR}
fi

log "Creating Bazel install base..."
ARCHIVE_DIR=${OUTPUT_DIR}/archive
mkdir -p ${ARCHIVE_DIR}/_embedded_binaries

# Dummy build-runfiles
cat <<'EOF' >${ARCHIVE_DIR}/_embedded_binaries/build-runfiles${EXE_EXT}
#!/bin/sh
mkdir -p $2
cp $1 $2/MANIFEST
EOF
chmod 0755 ${ARCHIVE_DIR}/_embedded_binaries/build-runfiles${EXE_EXT}

log "Creating process-wrapper..."
cat <<'EOF' >${ARCHIVE_DIR}/_embedded_binaries/process-wrapper${EXE_EXT}
#!/bin/sh
# Dummy process wrapper, does not support timeout
shift 2
stdout="$1"
stderr="$2"
shift 2

if [ "$stdout" = "-" ]
then
  if [ "$stderr" = "-" ]
  then
    "$@"
    exit $?
  else
    "$@" 2>"$stderr"
    exit $?
  fi
else
  if [ "$stderr" = "-" ]
  then
    "$@" >"$stdout"
    exit $?
  else
    "$@" 2>"$stderr" >"$stdout"
    exit $?
  fi
fi


"$@"
exit $?
EOF
chmod 0755 ${ARCHIVE_DIR}/_embedded_binaries/process-wrapper${EXE_EXT}

function build_jni() {
  local -r output_dir=$1

  case "${PLATFORM}" in
  msys*|mingw*)
    # We need JNI on Windows because some filesystem operations are not (and
    # cannot be) implemented in native Java.
    log "Building Windows JNI library..."

    local -r jni_lib_name="windows_jni.dll"
    local -r output="${output_dir}/${jni_lib_name}"
    local -r tmp_output="${NEW_TMPDIR}/jni/${jni_lib_name}"
    mkdir -p "$(dirname "$tmp_output")"
    mkdir -p "$(dirname "$output")"

    # Keep this `find` command in sync with the `srcs` of
    # //src/main/native:windows_jni
    local srcs=$(find src/main/native \
        -name 'windows_*.cc' -o -name 'windows_*.h')
    [ -n "$srcs" ] || fail "Could not find sources for Windows JNI library"

    # do not quote $srcs because we need to expand it to multiple args
    src/main/native/build_windows_jni.sh "$tmp_output" ${srcs}

    cp "$tmp_output" "$output"
    chmod 0555 "$output"

    JNI_FLAGS="-Dio.bazel.EnableJni=1 -Djava.library.path=${output_dir}"
    ;;

  *)
    # We don't need JNI on other platforms.
    JNI_FLAGS="-Dio.bazel.EnableJni=0"
    ;;
  esac
}

build_jni "${ARCHIVE_DIR}/_embedded_binaries"

cp src/main/tools/build_interface_so ${ARCHIVE_DIR}/_embedded_binaries/build_interface_so
cp src/main/tools/jdk.BUILD ${ARCHIVE_DIR}/_embedded_binaries/jdk.BUILD
cp $OUTPUT_DIR/libblaze.jar ${ARCHIVE_DIR}

# TODO(b/28965185): Remove when xcode-locator is no longer required in embedded_binaries.
log "Compiling xcode-locator..."
if [[ $PLATFORM == "darwin" ]]; then
  run /usr/bin/xcrun clang -fobjc-arc -framework CoreServices -framework Foundation -o ${ARCHIVE_DIR}/_embedded_binaries/xcode-locator tools/osx/xcode_locator.m
else
  cp tools/osx/xcode_locator_stub.sh ${ARCHIVE_DIR}/_embedded_binaries/xcode-locator
fi

function run_bazel_jar() {
  local command=$1
  shift
  "${JAVA_HOME}/bin/java" \
      -XX:+HeapDumpOnOutOfMemoryError -Xverify:none -Dfile.encoding=ISO-8859-1 \
      -XX:HeapDumpPath=${OUTPUT_DIR} \
      -Djava.util.logging.config.file=${OUTPUT_DIR}/javalog.properties \
      ${JNI_FLAGS} \
      -jar ${ARCHIVE_DIR}/libblaze.jar \
      --batch \
      --install_base=${ARCHIVE_DIR} \
      --output_base=${OUTPUT_DIR}/out \
      --install_md5= \
      --workspace_directory=${PWD} \
      --nofatal_event_bus_exceptions \
      ${BAZEL_DIR_STARTUP_OPTIONS} \
      ${BAZEL_BOOTSTRAP_STARTUP_OPTIONS:-} \
      $command \
      --ignore_unsupported_sandboxing \
      --startup_time=329 --extract_data_time=523 \
      --rc_source=/dev/null --isatty=1 \
      --ignore_client_env \
      --client_cwd=${PWD} \
      "${@}"
}
