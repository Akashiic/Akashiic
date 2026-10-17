# Akashic BigDim Login Fix

Patch **server-side** para Forge/Crucible 1.7.10 que corrige o crash/disconnect ao **entrar no servidor estando
dentro de um mundo/dimensão com ID fora de -128..127**: mundos do Multiverse/Bukkit (ex.: `Akashic/Evento` = 253),
pockets do DimensionalDoors (245+), etc.

## O problema

No 1.7.10 o pacote de login `S01PacketJoinGame` grava a dimensão com `writeByte`. A dimensão 253 sai no fio como
`-3` (253 − 256).

- Cliente Forge conectado **direto** sobrevive, porque o Forge manda a dimensão real no handshake (`ServerHello`
  `overrideDimension`).
- Atrás do **Velocity** (ou Bungee) não: o proxy lê o JoinGame como byte (`-3`) e, ao trocar o jogador de servidor,
  reenvia isso como `Respawn(-3)`. O cliente não conhece a dimensão -3 e crasha:

```
java.lang.IllegalArgumentException: Could not get provider type for dimension -3, does not exist
    at net.minecraftforge.common.DimensionManager.getProviderType(...)
    at net.minecraft.client.network.NetHandlerPlayClient.func_147280_a(...)   // handleRespawn
```

Teleportar para o mundo (`/mv tp`, portais) funciona, porque o `S07PacketRespawn` grava a dimensão como `int`.

## A correção

Um único mixin em `NetHandlerPlayServer.sendPacket`. Quando sai um `S01PacketJoinGame` cuja dimensão não cabe em um
byte, o servidor manda:

1. `S01PacketJoinGame` igual ao original, mas com dimensão `0` (overworld, que todo cliente conhece);
2. logo em seguida, `S07PacketRespawn` com a dimensão real (int), dificuldade, tipo de mundo e gamemode originais.

Assim o login vira exatamente o caminho de uma troca de dimensão normal, que já funciona hoje. O Respawn é enviado
antes de qualquer outro pacote de jogo (posição, chunks, inventário), então tudo o que vem depois já é aplicado no
mundo certo.

- Logins em dimensões dentro de -128..127 (a imensa maioria) **não são tocados**.
- Não muda o mundo, a posição nem os dados salvos do jogador; só como o login é comunicado ao cliente.
- **Não precisa instalar no cliente.** Não declara `@Mod`, então não entra na lista de mods do handshake FML.

## Instalação

1. Copie `akashicbigdimlogin-<versão>.jar` para a pasta `mods/` do **servidor modded (Crucible)**. Nada no Velocity
   nem nos clientes.
2. Requisito: UniMixins (já presente no servidor: `+unimixins-all-1.7.10-0.3.1.jar`).
3. Reinicie o servidor.

Quando a correção agir, o log mostra:

```
[AkashicBigDimLogin]: Login of Akashiic into dimension 253 (outside the JoinGame byte range): sent JoinGame(dim=0) + Respawn(dim=253)
```

## Validação feita

Servidor Forge 1.7.10 (10.13.4.1614) de dev + bot que fala o protocolo 1.7.10 e faz o handshake FML como cliente
modded. Um mod de teste (fora do jar) registrava a dimensão 253 e colocava o jogador nela ao logar.

| Cenário | Pacotes do servidor | O que o Velocity entregaria ao cliente | Dimensão final |
|---|---|---|---|
| **Sem o patch**, login na 253 | `JoinGame(-3)` | `JoinGame(-3)`, `Respawn(0)`, `Respawn(-3)` | **-3 → crash** |
| Com o patch, login na 253 | `JoinGame(0)`, `Respawn(253)`, posição, chunks | `JoinGame(0)`, `Respawn(-1)`, `Respawn(0)`, `Respawn(253)` | 253 |
| Com o patch, login na 0 | `JoinGame(0)`, posição, chunks (intocado) | `JoinGame(0)`, `Respawn(-1)`, `Respawn(0)` | 0 |
| Com o patch, login na -1 | `JoinGame(-1)`, posição, chunks (intocado) | `JoinGame(-1)`, `Respawn(0)`, `Respawn(-1)` | -1 |

## Observação: `AkashicDimLogin`

O servidor já tem um mod que registra `SERVER_FRAME kind=… dim=…` (logger `AkashicDimLogin`), com uma parte no
cliente (`FRAME_ORDER_BROKEN … correction window`). Com este patch ele passa a ver `JoinGame(dim=0)` seguido de
`Respawn(dim=253)`, o que é coerente com uma troca de dimensão normal. Se ele foi uma tentativa anterior de resolver
este mesmo bug, teste primeiro sem ele, e depois com os dois juntos.

## Build

```
./gradlew build
```

O jar final fica em `build/libs/` (o que não termina em `-dev`/`-sources`). Template:
[GTNewHorizons/ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) (RetroFuturaGradle).
