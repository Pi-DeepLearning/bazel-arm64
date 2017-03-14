param(
  [switch] $prerelease
)

choco uninstall bazel --force -y
if ($prerelease) {
  choco install bazel --verbose --debug --prerelease --force -y -s ".;https://chocolatey.org/api/v2/"
} else {
  choco install bazel --verbose --debug --force -y -s ".;https://chocolatey.org/api/v2/"
}

if ($LASTEXITCODE -ne 0)
{
  write-error @"
`$LASTEXITCODE was not zero.
Inspect the output from choco install above.
It should not have had errors.
"@
  exit 1
}

& bazel version
if ($LASTEXITCODE -ne 0)
{
  write-error @"
`$LASTEXITCODE was not zero.
Inspect the output from ``bazel version`` above.
It should have shown you bazel's version number.
"@
  exit 1
}

& bazel info
if ($LASTEXITCODE -ne 0)
{
  write-error @"
`$LASTEXITCODE was not zero.
Inspect the output from ``bazel info`` above.
It should have shown you bazel's information about the current workspace.
"@
  exit 1
}

write-host @"
This test just:
* uninstalled bazel (if it was installed)
* installed bazel from the package you built
* asserted that the installation did not return an error exit code
* ran ``bazel version`` and asserted non-error exit code
* ran ``bazel info`` and asserted non-error exit code

The bazel commands should now be repeated in the other shells. Should work in:
* powershell (probably what you just ran this in)
* cmd
* msys2
"@
