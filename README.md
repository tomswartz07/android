This is the Android client for [ownCloud][0].

The app performs file synchronization with an ownCloud server. Other ownCloud features may be added in the future, but they are not a priority right now.

Make sure you read [SETUP.md][1] when you start working on this project.

### Auto-sign ant bulds
Credentials for the secure key are stored in `secure.properties`.
File should contain two lines:
	key.store.password=<KEY STORE PASSWORD>
	key.alias.password=<KEY ALIAS PASSWORD>

[0]: https://github.com/owncloud/core
[1]: https://raw.github.com/owncloud/android/master/SETUP.md
