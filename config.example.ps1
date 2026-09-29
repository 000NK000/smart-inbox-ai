# Copy to config.local.ps1 and fill in your own values. Never commit that file.
# This file is loaded by the desktop and web startup scripts.
$env:REDIS_PASSWORD = ''
$env:GMAIL_EMAIL = ''
$env:GMAIL_APP_PASSWORD = ''
$env:QQ_EMAIL = ''
$env:QQ_AUTH_CODE = ''
$env:OUTLOOK_EMAIL = ''
$env:OUTLOOK_CLIENT_ID = ''
$env:H2_PASSWORD = ''
$env:MYSQL_ROOT_PASSWORD = ''
$env:ROCKETMQ_ACCESS_KEY = ''
$env:ROCKETMQ_SECRET_KEY = ''
# SHA-256 (64 hexadecimal characters) of a management password you choose.
# Without this setting, the credential-management panel stays locked.
$env:SMART_INBOX_MANAGEMENT_PASSWORD_SHA256 = ''
