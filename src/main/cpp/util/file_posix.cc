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
#include "src/main/cpp/util/file_platform.h"

#include <errno.h>
#include <dirent.h>  // DIR, dirent, opendir, closedir
#include <fcntl.h>   // O_RDONLY
#include <limits.h>  // PATH_MAX
#include <stdlib.h>  // getenv
#include <sys/stat.h>
#include <unistd.h>  // access, open, close, fsync
#include <utime.h>   // utime

#include <vector>

#include "src/main/cpp/util/errors.h"
#include "src/main/cpp/util/exit_code.h"
#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/strings.h"

namespace blaze_util {

using std::pair;
using std::string;

// TODO(bazel-team): implement all functions in file_windows.cc, use them from
// MSYS, remove file_posix.cc from the `srcs` of
// //src/main/cpp/util:file when building for MSYS, and remove all
// #ifndef __CYGWIN__ directives.
#ifndef __CYGWIN__
// Runs "stat" on `path`. Returns -1 and sets errno if stat fails or
// `path` isn't a directory. If check_perms is true, this will also
// make sure that `path` is owned by the current user and has `mode`
// permissions (observing the umask). It attempts to run chmod to
// correct the mode if necessary. If `path` is a symlink, this will
// check ownership of the link, not the underlying directory.
static bool GetDirectoryStat(const string &path, mode_t mode,
                             bool check_perms) {
  struct stat filestat = {};
  if (stat(path.c_str(), &filestat) == -1) {
    return false;
  }

  if (!S_ISDIR(filestat.st_mode)) {
    errno = ENOTDIR;
    return false;
  }

  if (check_perms) {
    // If this is a symlink, run checks on the link. (If we did lstat above
    // then it would return false for ISDIR).
    struct stat linkstat = {};
    if (lstat(path.c_str(), &linkstat) != 0) {
      return false;
    }
    if (linkstat.st_uid != geteuid()) {
      // The directory isn't owned by me.
      errno = EACCES;
      return false;
    }

    mode_t mask = umask(022);
    umask(mask);
    mode = (mode & ~mask);
    if ((filestat.st_mode & 0777) != mode && chmod(path.c_str(), mode) == -1) {
      // errno set by chmod.
      return false;
    }
  }
  return true;
}

static bool MakeDirectories(const string &path, mode_t mode, bool childmost) {
  if (path.empty() || IsRootDirectory(path)) {
    errno = EACCES;
    return false;
  }

  bool stat_succeeded = GetDirectoryStat(path, mode, childmost);
  if (stat_succeeded) {
    return true;
  }

  if (errno == ENOENT) {
    // Path does not exist, attempt to create its parents, then it.
    string parent = Dirname(path);
    if (!MakeDirectories(parent, mode, false)) {
      // errno set by stat.
      return false;
    }

    if (mkdir(path.c_str(), mode) == -1) {
      if (errno == EEXIST) {
        if (childmost) {
          // If there are multiple bazel calls at the same time then the
          // directory could be created between the MakeDirectories and mkdir
          // calls. This is okay, but we still have to check the permissions.
          return GetDirectoryStat(path, mode, childmost);
        } else {
          // If this isn't the childmost directory, we don't care what the
          // permissions were. If it's not even a directory then that error will
          // get caught when we attempt to create the next directory down the
          // chain.
          return true;
        }
      }
      // errno set by mkdir.
      return false;
    }
    return true;
  }

  return stat_succeeded;
}

class PosixPipe : public IPipe {
 public:
  PosixPipe(int recv_socket, int send_socket)
      : _recv_socket(recv_socket), _send_socket(send_socket) {}

  PosixPipe() = delete;

  virtual ~PosixPipe() {
    close(_recv_socket);
    close(_send_socket);
  }

  bool Send(const void *buffer, int size) override {
    return size >= 0 && write(_send_socket, buffer, size) == size;
  }

  int Receive(void* buffer, int size) override {
    return size < 0 ? -1 : read(_recv_socket, buffer, size);
  }

 private:
  int _recv_socket;
  int _send_socket;
};

IPipe* CreatePipe() {
  int fd[2];
  if (pipe(fd) < 0) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR, "pipe()");
  }

  if (fcntl(fd[0], F_SETFD, FD_CLOEXEC) == -1) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR,
         "fcntl(F_SETFD, FD_CLOEXEC) failed");
  }

  if (fcntl(fd[1], F_SETFD, FD_CLOEXEC) == -1) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR,
         "fcntl(F_SETFD, FD_CLOEXEC) failed");
  }

  return new PosixPipe(fd[0], fd[1]);
}

pair<string, string> SplitPath(const string &path) {
  size_t pos = path.rfind('/');

  // Handle the case with no '/' in 'path'.
  if (pos == string::npos) return std::make_pair("", path);

  // Handle the case with a single leading '/' in 'path'.
  if (pos == 0) return std::make_pair(string(path, 0, 1), string(path, 1));

  return std::make_pair(string(path, 0, pos), string(path, pos + 1));
}

bool ReadFile(const string &filename, string *content, int max_size) {
  int fd = open(filename.c_str(), O_RDONLY);
  if (fd == -1) return false;
  bool result =
      ReadFrom([fd](void *buf, int len) { return read(fd, buf, len); }, content,
               max_size);
  close(fd);
  return result;
}

bool WriteFile(const void *data, size_t size, const string &filename) {
  UnlinkPath(filename);  // We don't care about the success of this.
  int fd =
      open(filename.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0755);  // chmod +x
  if (fd == -1) {
    return false;
  }
  bool result = WriteTo(
      [fd](const void *buf, size_t bufsize) { return write(fd, buf, bufsize); },
      data, size);
  int saved_errno = errno;
  if (close(fd)) {
    return false;  // Can fail on NFS.
  }
  errno = saved_errno;  // Caller should see errno from write().
  return result;
}

bool UnlinkPath(const string &file_path) {
  return unlink(file_path.c_str()) == 0;
}

bool PathExists(const string& path) {
  return access(path.c_str(), F_OK) == 0;
}
#endif  // not __CYGWIN__

string MakeCanonical(const char *path) {
  char *resolved_path = realpath(path, NULL);
  if (resolved_path == NULL) {
    return "";
  } else {
    string ret = resolved_path;
    free(resolved_path);
    return ret;
  }
}

#ifndef __CYGWIN__
static bool CanAccess(const string &path, bool read, bool write, bool exec) {
  int mode = 0;
  if (read) {
    mode |= R_OK;
  }
  if (write) {
    mode |= W_OK;
  }
  if (exec) {
    mode |= X_OK;
  }
  return access(path.c_str(), mode) == 0;
}

bool CanReadFile(const std::string &path) {
  return !IsDirectory(path) && CanAccess(path, true, false, false);
}

bool CanExecuteFile(const std::string &path) {
  return !IsDirectory(path) && CanAccess(path, false, false, true);
}

bool CanAccessDirectory(const std::string &path) {
  return IsDirectory(path) && CanAccess(path, true, true, true);
}

bool IsDirectory(const string& path) {
  struct stat buf;
  return stat(path.c_str(), &buf) == 0 && S_ISDIR(buf.st_mode);
}

bool IsRootDirectory(const string &path) {
  return path.size() == 1 && path[0] == '/';
}

bool IsAbsolute(const string &path) { return !path.empty() && path[0] == '/'; }

void SyncFile(const string& path) {
  const char* file_path = path.c_str();
  int fd = open(file_path, O_RDONLY);
  if (fd < 0) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR,
         "failed to open '%s' for syncing", file_path);
  }
  if (fsync(fd) < 0) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR, "failed to sync '%s'",
         file_path);
  }
  close(fd);
}

class PosixFileMtime : public IFileMtime {
 public:
  PosixFileMtime()
      : near_future_(GetFuture(9)),
        distant_future_({GetFuture(10), GetFuture(10)}) {}

  bool GetIfInDistantFuture(const string &path, bool *result) override;
  bool SetToNow(const string &path) override;
  bool SetToDistantFuture(const string &path) override;

 private:
  // 9 years in the future.
  const time_t near_future_;
  // 10 years in the future.
  const struct utimbuf distant_future_;

  static bool Set(const string &path, const struct utimbuf &mtime);
  static time_t GetNow();
  static time_t GetFuture(unsigned int years);
};

bool PosixFileMtime::GetIfInDistantFuture(const string &path, bool *result) {
  struct stat buf;
  if (stat(path.c_str(), &buf)) {
    return false;
  }
  // Compare the mtime with `near_future_`, not with `GetNow()` or
  // `distant_future_`.
  // This way we don't need to call GetNow() every time we want to compare and
  // we also don't need to worry about potentially unreliable time equality
  // check (in case it uses floats or something crazy).
  *result = (buf.st_mtime > near_future_);
  return true;
}

bool PosixFileMtime::SetToNow(const string &path) {
  time_t now(GetNow());
  struct utimbuf times = {now, now};
  return Set(path, times);
}

bool PosixFileMtime::SetToDistantFuture(const string &path) {
  return Set(path, distant_future_);
}

bool PosixFileMtime::Set(const string &path, const struct utimbuf &mtime) {
  return utime(path.c_str(), &mtime) == 0;
}

time_t PosixFileMtime::GetNow() {
  time_t result = time(NULL);
  if (result == -1) {
    pdie(blaze_exit_code::INTERNAL_ERROR, "time(NULL) failed");
  }
  return result;
}

time_t PosixFileMtime::GetFuture(unsigned int years) {
  return GetNow() + 3600 * 24 * 365 * years;
}

IFileMtime *CreateFileMtime() { return new PosixFileMtime(); }

// mkdir -p path. Returns true if the path was created or already exists and
// could
// be chmod-ed to exactly the given permissions. If final part of the path is a
// symlink, this ensures that the destination of the symlink has the desired
// permissions. It also checks that the directory or symlink is owned by us.
// On failure, this returns false and sets errno.
bool MakeDirectories(const string &path, unsigned int mode) {
  return MakeDirectories(path, mode, true);
}

string GetCwd() {
  char cwdbuf[PATH_MAX];
  if (getcwd(cwdbuf, sizeof cwdbuf) == NULL) {
    pdie(blaze_exit_code::INTERNAL_ERROR, "getcwd() failed");
  }
  return string(cwdbuf);
}

bool ChangeDirectory(const string& path) {
  return chdir(path.c_str()) == 0;
}
#endif  // not __CYGWIN__

void ForEachDirectoryEntry(const string &path,
                           DirectoryEntryConsumer *consume) {
  DIR *dir;
  struct dirent *ent;

  if ((dir = opendir(path.c_str())) == NULL) {
    // This is not a directory or it cannot be opened.
    return;
  }

  while ((ent = readdir(dir)) != NULL) {
    if (!strcmp(ent->d_name, ".") || !strcmp(ent->d_name, "..")) {
      continue;
    }

    string filename(blaze_util::JoinPath(path, ent->d_name));
    bool is_directory;
    if (ent->d_type == DT_UNKNOWN) {
      struct stat buf;
      if (lstat(filename.c_str(), &buf) == -1) {
        die(blaze_exit_code::INTERNAL_ERROR, "stat failed");
      }
      is_directory = S_ISDIR(buf.st_mode);
    } else {
      is_directory = (ent->d_type == DT_DIR);
    }

    consume->Consume(filename, is_directory);
  }

  closedir(dir);
}

}  // namespace blaze_util
