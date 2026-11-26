# Security policy

## Supported versions

| Version | Supported |
| --- | --- |
| 1.0.x | Yes |

Security fixes are released as new patch versions on the latest release line.

## Reporting a vulnerability

Report vulnerabilities privately through GitHub: on the
[repository's Security tab](https://github.com/Venuss1337/filasignal/security), choose **Report a vulnerability**.
Please do not open a public issue for a security problem or publish exploit details before a fix is available.

Useful reports include the FilaSignal, Minecraft, Fabric Loader and Fabric API versions, whether it affects a
dedicated server, single player or both, and the steps or program that reproduce it.

## Scope

In scope: anything that lets a player or a FilaScript program act beyond what FilaSignal intends, such as using or
reading another player's devices, programs or storage without trust, escaping the script sandbox (files, network,
reflection, arbitrary Java or Minecraft objects), bypassing execution or resource limits to stall or crash a server,
loading chunks without permission, or corrupting saved data.

Out of scope: problems in Minecraft, Fabric or other mods themselves; behavior an owner or server administrator
deliberately configured; and the documented trust model, for example that display text is visible to nearby
players or that trusted players can edit programs.

The design rules and the most recent review are in
[docs/engineering/SECURITY-AND-RUNTIME.md](docs/engineering/SECURITY-AND-RUNTIME.md). FilaSignal has been reviewed
and tested by its maintainer; it has not had an independent security audit.
