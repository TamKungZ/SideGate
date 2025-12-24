# SideGate

SideGate is a plugin for Minecraft Servers (Spigot/Paper) designed to facilitate a hybrid login environment. It allows server administrators to operate in Online-Mode (Premium) for enhanced security and skin support, while simultaneously granting access to specific Offline-Mode (Cracked) players through a controlled whitelist.

## Concept
Operate a secure, premium server while maintaining a designated entry point for authorized guest players.

## Features
- **Hybrid Login System:** Supports the concurrent connection of legitimate Premium players (standard authentication) and Offline players (bypass authentication) within the same server instance.
- **Selective Bypass:** Administrators can whitelist specific usernames, allowing only those designated individuals to bypass Mojang's license verification.
- **Allow All Mode:** A configuration option to permit all connections to bypass verification, effectively simulating Offline Mode behavior while keeping the server properties set to Online Mode.
- **Guest Restrictions:** Configurable options to enforce specific GameModes and display custom welcome messages for players entering via the bypass method.
- **Fully Configurable:** All settings, including messages, operational modes, and whitelists, are managed via `config.yml`.

## Installation
1. Download the latest `.jar` file from the Releases section.
2. Place the file into the `plugins` directory of your server.
3. Requirement: Ensure that ProtocolLib is installed on the server.
4. Configure `server.properties` to `online-mode=true`.
5. Start or restart the server to generate the configuration files.

## Configuration
Edit the file located at `plugins/SideGate/config.yml`:
```yaml
# SideGate Configuration

enable-guest-mode: true  # Enable or disable the SideGate system.
allow-all-guests: false  # If set to true: Everyone (both Premium and Cracked) will bypass authentication.
                            # If set to false: Only usernames listed in 'allowed-guests' will bypass authentication.
allowed-guests:
  - "PlayerName1"
  - "PlayerName2"
guest-settings:
  default-gamemode: "SURVIVAL"
  chat-prefix: "&7[Guest] &r"
```

## Technical Details
SideGate operates by intercepting the Minecraft login protocol via ProtocolLib:
- Upon a client connection attempt, the plugin inspects the username.
- If the username is found in the whitelist (or if "Allow All" is enabled), it injects a signal to the server indicating that authentication is complete (`Force State`) and cancels the standard Encryption Request packet.
- The authorized guest enters with an Offline UUID (v3).
- Premium players who are not whitelisted proceed through standard Mojang authentication, receiving an Online UUID (v4) and retaining their skins.

## Requirements
- Java: Version 17 or higher.
- Server Software: Spigot, Paper, or Purpur (Compatible with Minecraft 1.20.x).
- Dependency: ProtocolLib 5.0 or higher.

## Disclaimer
Permitting offline-mode connections carries inherent security risks, such as username impersonation. It is strongly recommended to utilize this plugin in conjunction with an authentication plugin (e.g., AuthMe) to require guests to register and log in with a password. Players connecting via SideGate bypass will not display their official skins, as they do not authenticate with Mojang's session servers.

## License
This project is licensed under CC BY-NC-ND 4.0. You may view and share this repository with attribution, but you may NOT modify or use it for commercial purposes.
[Creative Commons License](http://creativecommons.org/licenses/by-nc-nd/4.0/)
