Releasing
=========

1. Change the version in `gradle.properties` to a non-SNAPSHOT version.
2. Update the `CHANGELOG.md` for the impending release.
3. `git commit -am "Prepare for release X.Y.Z."` (where X.Y.Z is the new version)
4. `git tag -a vX.Y.Z -m "Version X.Y.Z"` (where X.Y.Z is the new version)
5. Update the `gradle.properties` to the next SNAPSHOT version.
6. `git commit -am "Prepare next development version."`
7. `git push && git push --tags`
