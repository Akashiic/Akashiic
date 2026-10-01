# ProtocolObelisk 1.9.16-EVOLUTION — ProMenus HF2 Build Report

## Base

HF2 foi construído incrementalmente sobre os JARs HF1 que produziram a rodada de logs de 2026-09-14.

## Delta binário exato

Paper:
- substituída: `br/com/atmbrasil/lobby/paper/ProMenusBungeeCompatibility.class`
- adicionada: `br/com/atmbrasil/lobby/paper/ProMenusBungeeCompatibility$TraceListener.class`
- adicionada: `META-INF/AKASHIC-PROMENUS-HF2.txt`
- nenhuma entrada removida

Velocity:
- substituída: `br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.class`
- adicionada: `META-INF/AKASHIC-PROMENUS-HF2.txt`
- nenhuma entrada removida

O marker HF1 e os manifests originais são preservados.

## Gates

- `javac --release 21 -Xlint:all -Werror`: PASS
- classfile major 65: PASS
- CRC JAR/ZIP: PASS
- entradas ZIP duplicadas: nenhuma
- Paper control probe `GetServer` pelo plugin ProMenus: PASS (`11 bytes`)
- Velocity modern `bungeecord:main` observation: PASS
- Velocity legacy `BungeeCord` observation para protocolo 5: PASS
- `ForwardResult` permaneceu forward no probe: PASS
- `ServerPreConnectEvent` permaneceu com destino original: PASS
- source gate sem `setResult`, `requestServer` ou `createConnectionRequest` no observer de preconnect: PASS
- auditor de YAML com os menus HF1 empacotados: PASS

## Segurança arquitetural

HF2 não cria connection request, não chama `setResult` no preconnect, não registra `bungeecord:main` como sink do ProtocolObelisk e não altera a política de rota. O único tráfego sintético é `GetServer`, consulta BungeeCord sem efeito de transferência.
