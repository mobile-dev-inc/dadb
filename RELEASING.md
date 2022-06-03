Releasing
=========

1. Create a new branch for the release `git checkout -b release-vX.Y.Z` (where X.Y.Z is the new version)
2. Change the version in `gradle.properties` to a non-SNAPSHOT version.
3. Update the `CHANGELOG.md` for the impending release.
4. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
5. Submit a PR with the changes against the `master` branch

After merging the PR, tag the release:

6. `git tag -a vX.Y.Z -m "Version X.Y.Z"` (where X.Y.Z is the new version)
7.  git push --tags

After this is done, create a new branch to prepare for the next development version:

8. `git checkout master && git pull && git checkout -b prepare-X.Y.Z-SNAPSHOT` (where X.Y.Z is the new development version)
9. Update the `gradle.properties` to the next SNAPSHOT version.
10. `git commit -am "Prepare next development version."`
11. Submit a PR with the changes against the `master` branch and merge it
