Releasing
=========

1. Create a new branch for the release `git checkout -b vX.Y.Z` (where X.Y.Z is the new version)
2. Change the version in `gradle.properties` to a non-SNAPSHOT version.
3. Update the `CHANGELOG.md` for the impending release.
4. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)

After merging the PR, tag the release:

5. `git tag -a vX.Y.Z -m "Version X.Y.Z"` (where X.Y.Z is the new version)
6.  git push --tags

After this is done, create a new branch to prepare for the next development version:

7. `git checkout -b X.Y.Z-SNAPSHOT` (where X.Y.Z is the new development version)
8. Update the `gradle.properties` to the next SNAPSHOT version.
9. `git commit -am "Prepare next development version."`
10. Raise and merge the PR
