"""Safety helpers shared by the android-* skill scripts.

1. Which repository a script may act on (check_repo). A script acts only on
   the repository that contains it: `--repo` must be the folder above the
   script's `.claude/` folder. A copy of a script elsewhere, or a `--repo`
   pointing at another repository, is refused.

2. Where a script may create files (write_new). Every file is new, never
   overwritten, and lies either
   - in a private subfolder of a temporary directory: /tmp (/private/tmp on
     macOS) or the per-user macOS temp folder, never directly in either, and
     only through folders owned by the current user that no one else can
     write to; or
   - inside the repository, only under folders the caller names.
   The temp roots are fixed: $TMPDIR is ignored, so a command prefix cannot
   widen them. Symlinks are resolved first, folders are created one level at a
   time and re-checked, and the file is opened with O_EXCL | O_NOFOLLOW.

3. Which files a script may read (check_readable): the repository, or a
   temporary directory.

4. How a script runs git (git_command): no optional locks, no fsmonitor, no
   commit signature checks, no GIT_* variables from the caller's environment.
   Diff callers also pass --no-ext-diff and --no-textconv. Residual risk: a
   clean filter configured in the repository's own .git/config still runs when
   git compares working-tree files.

Loaded by path (the scripts run with `python3 -I`). Compatible with Python 3.9.
"""

import os
import stat
import subprocess

GIT_SAFE_OPTIONS = ["--no-pager", "--no-optional-locks", "-c", "core.fsmonitor=false",
                    "-c", "log.showSignature=false"]
DIFF_SAFE_OPTIONS = ["--no-ext-diff", "--no-textconv"]


# ---------------------------------------------------------------- repository

def script_root(script_file):
    """The folder that holds the `.claude/` folder this script lives in."""
    real = os.path.realpath(script_file)
    marker = os.sep + ".claude" + os.sep
    if marker not in real:
        raise ValueError("script %s is not inside a .claude/ folder" % script_file)
    return real.split(marker)[0]


def check_repo(repo, script_file):
    """Return the real repository root, or raise ValueError."""
    root = script_root(script_file)
    if os.path.realpath(repo) != root:
        raise ValueError("refusing --repo %s: this script only acts on %s" % (repo, root))
    return root


# ---------------------------------------------------------------- git

def git_env():
    env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    env["GIT_TERMINAL_PROMPT"] = "0"
    return env


def git_command(repo, *args):
    return ["git", "-C", repo] + GIT_SAFE_OPTIONS + list(args)


def run_git(repo, *args):
    return subprocess.run(git_command(repo, *args), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          env=git_env())


# ---------------------------------------------------------------- temp roots

def temp_roots():
    roots = {os.path.realpath("/tmp")}
    try:
        user_tmp = subprocess.run(["/usr/bin/getconf", "DARWIN_USER_TEMP_DIR"], stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL, env={}).stdout.decode().strip()
        if user_tmp:
            roots.add(os.path.realpath(user_tmp))
    except OSError:
        pass
    return sorted(roots)


def _under(path, root):
    return path == root or path.startswith(root.rstrip(os.sep) + os.sep)


def in_temp(path):
    real = os.path.realpath(path)
    return any(_under(real, root) for root in temp_roots())


def _private_dir(path):
    """A real directory (not a symlink) owned by this user, not writable by others."""
    try:
        info = os.lstat(path)
    except FileNotFoundError:
        return None
    return stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and not info.st_mode & 0o022


def _components(base, parent):
    """Folders from just below base down to parent, in order."""
    relative = os.path.relpath(parent, base)
    if relative == ".":
        return []
    parts, current = [], base
    for part in relative.split(os.sep):
        current = os.path.join(current, part)
        parts.append(current)
    return parts


def check_readable(path, repo):
    real = os.path.realpath(path)
    if _under(real, os.path.realpath(repo)) or any(_under(real, root) for root in temp_roots()):
        return real
    raise ValueError("refusing to read %s: only the repository and temporary directories are allowed" % path)


def check_new_file(path, repo=None, repo_allowed=()):
    """Return (resolved target, base folder), or raise ValueError with the reason."""
    target = os.path.abspath(path)
    parent = os.path.realpath(os.path.dirname(target))
    real = os.path.join(parent, os.path.basename(target))
    if repo:
        repo_real = os.path.realpath(repo)
        if _under(real, repo_real):
            allowed = [os.path.join(repo_real, folder) for folder in repo_allowed]
            matches = [folder for folder in allowed if _under(real, folder)]
            if not matches:
                if not repo_allowed:
                    raise ValueError("refusing to write %s: inside the repository" % path)
                names = " and ".join(folder.rstrip("/") + "/" for folder in repo_allowed)
                raise ValueError("refusing to write %s: inside the repository, only %s allowed" % (path, names))
            if os.path.lexists(target):
                raise ValueError("refusing to overwrite %s" % path)
            return real, repo_real
    roots = [root for root in temp_roots() if _under(real, root)]
    if not roots:
        raise ValueError("refusing to write %s: outside the temporary directories (%s)"
                         % (path, ", ".join(temp_roots())))
    root = max(roots, key=len)
    if parent == root:
        raise ValueError("refusing to write %s: directly in %s; use a private subfolder" % (path, root))
    for folder in _components(root, parent):
        if _private_dir(folder) is False:
            raise ValueError("refusing to write %s: %s is not a private folder of this user" % (path, folder))
    if os.path.lexists(target):
        raise ValueError("refusing to overwrite %s" % path)
    return real, root


def write_new(path, text, repo=None, repo_allowed=()):
    real, base = check_new_file(path, repo, repo_allowed)
    # Create missing folders one level at a time, re-checking each one, so a
    # symlink planted while we work cannot redirect the write.
    for folder in _components(base, os.path.dirname(real)):
        if not os.path.lexists(folder):
            os.mkdir(folder, 0o700)
        if not _private_dir(folder):
            raise ValueError("refusing to write %s: %s is not a private folder of this user" % (path, folder))
    if os.path.realpath(os.path.dirname(real)) != os.path.dirname(real):
        raise ValueError("refusing to write %s: its folder changed while it was being created" % path)
    descriptor = os.open(real, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(text)
    return real
