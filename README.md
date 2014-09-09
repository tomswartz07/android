# Penn Manor ownCloud Android Client
This is the Android client for [ownCloud][0], forked from the official [ownCloud Android App][1].

The app performs file synchronization with an ownCloud server.
Other ownCloud features may be added in the future, but they are not a priority right now.

## Set Up Documentation
This app is currently configured for automated builds.
The following documentation will describe how to set up your environment to utilize the auto-build system, which may easily integrate with Continuous Integration software such as Jenkins.

All .apk generation is performed by the command line tool `ant`.

### Prerequisites
- Android SDK *(Minimum API version 19.1)*
- Apache Ant *(Minimum version 1.8)*
- All Android Platform tools in current $PATH

### Setting up

- Clone the repo: `git clone https://github.com/tomswartz07/android.git`
- Move to the project folder with `cd android`
- Make official ownCloud repo known as upstream: `git remote add upstream git@github.com:owncloud/android.git`
- Make sure to get the latest changes from official android/develop branch: `git pull upstream master`
- Complete the setup of project properties and resolve pending dependencies running `setup_env.bat` or `./setup_env.sh`.
- Install the App Keystore file to the path `$PROJECTROOT/keystore/keystore_file.keystore`

### Auto-sign ant builds
In order to fully automate the build of the .apk file, a single file must be created to allow ant to autosign the app.
Credentials for the secure key are stored in `secure.properties`.
The file should contain two lines:

	key.store.password=<KEY STORE PASSWORD>
	key.alias.password=<KEY ALIAS PASSWORD>

## Building with Ant:

NOTE: You must have the Android SDK `tools/`, and `platforms-tools/` folders in your environment path variable to perform this step.

- Run `ant clean`
- Run `ant debug` to generate a debug-ready version of the ownCloud app. All generated files are output to the `bin/` folder.
- Test the debug version of the app for issues, correct if necessary.
- Run `ant release` to generate a release-ready verision of the ownCloud app. Again, the file is output to the `bin/` folder.
- Quality Assurance Test the release version of the .apk and upload to the Google Play account.

## Pulling and Merging changes from upstream
Because this version of the Android app depends on the upstream owncloud/android project for updates, a frequent 'merge' process should be performed.

The current app files follow a different naming convention than upstream, such that both apps could be made available in the Google Play Store.
Please use caution when merging the upstream files to the project.


[0]: https://github.com/owncloud/core
[1]: https://github.com/owncloud/android/
[2]: https://raw.github.com/owncloud/android/master/SETUP.md
