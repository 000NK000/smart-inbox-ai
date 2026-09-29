# Repository and local configuration

See the [README](README.md) for the English project overview, synthetic screenshots, read-only UI demo, setup instructions, and test commands.

## Configure a local installation

Copy `config.example.ps1` to the ignored `config.local.ps1` and enter your own settings. Never commit that local file. Public examples contain placeholders or documented development defaults, not credentials for a working installation.

Outlook's desktop bridge requires classic Outlook signed into your own account. Microsoft Graph requires your own permitted app registration and authorization. The repository does not contain either account's tokens.

## Keep personal data outside Git

- Mail passwords, app authorization codes, OAuth/API credentials, and management-password verifiers.
- Databases, actual messages, notes, application records, screenshots of private accounts, and backup exports.
- Runtime logs, credential vaults, their encryption keys, token directories, and browser sessions.
- Private interview preparation documents and machine-specific debugging scripts.
- Dependency caches, compiled applications, model weights, and Docker volumes.

Review staged changes before each push. Ignoring a file does not remove versions already committed to Git history. If a credential was ever committed, revoke or rotate it and follow GitHub's [sensitive-data removal guidance](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository).

The demonstration screenshots and fixtures in this repository use fictional data. They can be regenerated from the separate UI demo without connecting a mailbox or using the personal application's database.
