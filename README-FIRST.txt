FoldBlur V2.1 fixed build

This revision removes use of hidden Android display APIs that caused the V2 GitHub Actions compilation failure.
It also updates the GitHub Actions workflow to current Node 24-compatible action versions.

For an existing FoldBlur-V2 repository, upload the contents of this folder at the repository root and commit.
Your existing .github/workflows/build-apk.yml may be overwritten by the included updated workflow if your upload method includes dot-folders.
If it is not overwritten, the existing workflow can still build this corrected source; its Node warnings are not fatal.
