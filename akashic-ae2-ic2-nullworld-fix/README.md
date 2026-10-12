# Akashic AE2 IC2 NullWorld Fix

Coremod **server-side** (Forge 1.7.10 / Crucible) que corrige o crash do Advanced Inscriber do AE2 GTNH com IC2:

```
java.lang.NullPointerException: world is null
    at ic2.api.energy.event.EnergyTileEvent.<init>(EnergyTileEvent.java:20)
    at appeng.integration.modules.IC2.addToEnergyNet(IC2.java:78)
    at appeng.tile.powersink.IC2.addToENet(IC2.java:87)
    at appeng.tile.powersink.IC2.setPowerSides(IC2.java:97)
    at appeng.tile.misc.TileAdvancedInscriber.<init>(TileAdvancedInscriber.java:76)
```

| | |
|---|---|
| Afeta | AE2 GTNH `rv3-beta-941-GTNH` até `rv3-beta-1077-GTNH`, com IC2 experimental instalado |
| Corrigido no AE2 | `rv3-beta-1078-GTNH` ([commit 3ef1451](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial/commit/3ef14510186b0fd76dc0d7ef381e42bc29740d78)) |
| Lado | Só servidor. O cliente não precisa (lá o `Platform.isServer()` é falso). |
| Testado com | bytecode real do `appliedenergistics2-rv3-beta-1035-GTNH.jar`, ASM 5.0.3 e ASM 9.7, Java 21 |

## Causa

1. O construtor do `TileAdvancedInscriber` chama `setPowerSides(EnumSet.allOf(...))`.
2. Na versão GTNH, `appeng.tile.powersink.IC2#setPowerSides` chama `addToENet()`, que **não verifica se o
   `worldObj` é nulo** (o AE2 original verificava).
3. Dentro do construtor o tile ainda não tem mundo, então o AE2 dispara o `EnergyTileLoadEvent` do IC2 com
   `world == null` e o IC2 lança `NullPointerException: world is null`.

Ou seja: **qualquer** construção de um Advanced Inscriber na thread do servidor quebra:

- **Colocar o bloco**: o bloco é gravado no chunk, mas o tile entity não é criado. O jogador toma erro e fica
  um **bloco órfão** (bloco sem TE) no mundo.
- **Carregar um chunk** com um inscriber salvo: o Forge pega a exceção, loga `Skipping BlockEntity` e
  **descarta o TE** (inventário e upgrades se perdem quando o chunk salva).
- **Qualquer `world.getTileEntity()` num bloco órfão**: o Minecraft tenta recriar o TE na hora, a exceção
  sobe e **derruba o servidor**. Foi isso que aconteceu no crash.

No crash de 12/10/2026 às 13:17:45, o gatilho foi o `CoroUtil` (`DynamicDifficulty.tickPlayer`). A cada tick
ele percorre os jogadores do overworld que **não estão no chão** e chama `addCollisionBoxesToList` nos blocos
3×3×3 em volta deles. Ao logar, o jogador ainda não está "no chão" (`onGround = false`) até o primeiro pacote
de movimento. O `D0okZ` entrou em `world` (5773.67, 63.42, -3798.09) e o servidor caiu no mesmo segundo. A
colisão do bloco do AE2 chama `getTileEntity`, que tentou recriar o TE do inscriber órfão.

> O bloco órfão deve estar em **world, x 5772–5774, y 62–64, z -3800 a -3798**. Sem o patch, isso vira
> crash loop: o servidor cai sempre que alguém pular, cair ou logar ali do lado.

## O que o patch faz

Injeta, via ASM, uma guarda no começo de `appeng.tile.powersink.IC2#addToENet()`:

```java
private void addToENet() {
    if (this.worldObj == null) return;   // <- injetado (field_145850_b)
    ... código original ...
}
```

- No construtor o mundo é nulo, então o método só retorna e o construtor termina normalmente.
- O tile entra na energy net do IC2 pelo `onReady()`, que o AE2 chama no tick seguinte, quando o tile já
  tem mundo. A energia EU continua funcionando.
- Blocos órfãos se **curam sozinhos**: no próximo `getTileEntity` o TE é criado vazio e passa a funcionar.
  Itens que já se perderam não voltam.
- Não mexe em nenhuma outra classe. Se o método não existir (outra versão do AE2) ou já tiver a guarda, o
  patch não faz nada e só registra isso no log. Erro no patch também deixa a classe original intacta.

## Instalação

1. Pare o servidor.
2. Copie `dist/Akashic-AE2-IC2-NullWorldFix-1.0.0-serveronly.jar` para a pasta `mods/` do **servidor**.
3. Suba o servidor e confira no log:
   ```
   [FML/]: Injecting coremod AkashicAE2IC2Fix {br.com.redeakashic.ae2ic2fix.AkashicAE2FixPlugin} class transformers
   [AkashicAE2IC2Fix/]: Patched appeng.tile.powersink.IC2#addToENet: null-world guard injected ...
   ```
   Na lista de mods de um crash report ele aparece como `akashic_ae2_ic2_nullworld_fix{1.0.0}`.

Os clientes não precisam do jar. Ele não registra canal de rede nem entra na checagem de mods do handshake.

### Antes de instalar (emergência)

- Peça para ninguém colocar Advanced Inscriber, ou bloqueie o item temporariamente (BlockLimiter /
  FinalForgeRestrictor).
- Não tente remover o bloco órfão com WorldEdit antes do patch: mexer no bloco também pode chamar
  `getTileEntity` e derrubar o servidor.

### Conferir perdas antigas

Procure nos logs antigos por TEs descartados:

```
grep -n -E "world is null|Skipping BlockEntity|AdvancedInscriber" logs/*.log*
```

### Quando remover

Depois de atualizar o AE2 para `rv3-beta-1078-GTNH` ou mais novo, o mod deixa de ser necessário. Ele continua
inofensivo lá (o `addToENet` não mudou até o master atual), então dá para remover sem pressa.

## Build e testes

Precisa de JDK 9+ (o bytecode gerado é Java 8). As dependências são baixadas para `libs/` e conferidas por SHA-256.

```
./build.sh
```

O `build.sh` compila o mod, gera o jar em `dist/` e roda `NullWorldGuardTest`. O teste usa o
`appeng/tile/powersink/IC2.class` **real** do jar do AE2 1035, cercado de stubs mínimos (`src/test/stubs`),
e confirma:

- sem patch, o construtor do inscriber lança exatamente `NullPointerException: world is null`;
- com patch, o construtor passa e não mexe na energy net;
- depois que o tile ganha mundo, o `onReady()` registra o tile no IC2 uma única vez;
- outras classes passam intactas e aplicar o patch duas vezes não muda nada;
- o fluxo completo funciona através de um `LaunchClassLoader` real (launchwrapper 1.12), como no FML.

O teste não roda um servidor Forge completo. O carregamento do coremod pelo FML usa o mesmo padrão
(`FMLCorePlugin` no manifest) dos outros coremods `br.com.redeakashic.*` que o servidor já carrega.
