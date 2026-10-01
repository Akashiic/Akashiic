# Diagnóstico — lag do servidor Crucible 1.7.10 (TEOF 3.2)

Data: 2026-10-01. Fontes analisadas: spark sampler `HVoU6pheoX` (5 min, 0 jogadores), spark heap summary `pgAqRVMdH5`,
`latest.log` / `fml-server-latest.log` do boot das 14:02, modpack `TEOF-3.2.zip` (jars reais), Crucible `staging-67b53034` (fonte e build).

Legenda: **[medido]** = número lido dos arquivos ou do servidor de teste; **[código]** = lido do bytecode real (V33a / V33b-ForbiddenFix1 /
Crucible); **[estimativa]** = conta derivada, não medida diretamente em produção.

## 1. Sintoma: o servidor está sem memória, não sem CPU

| Dado | Valor | Fonte |
|---|---|---|
| Heap usado / máximo | 16,84 GB / 17,18 GB (Old Gen 16,8 GB) | heap summary [medido] |
| Full GC (G1 Old Generation) | 39–40 coletas, média **3,4 s**, uma a cada ~11–13 s | heap summary / sampler [medido] |
| TPS | 5,1 → 2,6 ao longo de 5 min; MSPT mediana 1,0–1,4 s; máx. 29,3 s | sampler `timeWindowStatistics` [medido] |
| Jogadores | 0 | idem |
| Tempo do thread principal em GC | 24 % | sampler [medido] |

Com o heap cheio, o JVM passa a maior parte do tempo coletando lixo: daí o lag e o atraso de comandos.

## 2. O que ocupa o heap: chunks carregados sem ninguém online

| Classe | Instâncias | Tamanho |
|---|---|---|
| `byte[]` | 11,57 M | **10,18 GB** |
| `net.minecraft.world.chunk.Chunk` | **124.499** | |
| `ExtendedBlockStorage` / `NibbleArray` | 683.679 / 3,26 M | (dados de bloco dos chunks, dentro dos `byte[]`) |
| `TileEntityCrystalPylon` | **19.122** | |
| `PylonGenerator$PylonEntry` | **19.087** | |
| `Coordinate` / `BlockKey` / `FilledBlockArray$EmptyCheck` | 21,4 M / 9,3 M / 3,8 M | 514 MB / 223 MB / 92 MB (estruturas dos pylons) |
| `HashMap$Node` | 39 M | 1,26 GB |
| Entidades | 156.634 (88.707 itens no chão), todas no Overworld | |

[medido] Os chunks com entidades estão espalhados **uniformemente** por ±896 chunks (±14.300 blocos), não concentrados em bases:
padrão de algo que carrega chunks "pelo mundo", não de jogadores.

## 3. Quem carrega os chunks: os Crystal Pylons

Sampler, thread principal [medido]:

```
42,1 %  TileEntityCrystalPylon.updateEntity
35,0 %    Coordinate.getBlock -> World.getBlock -> ChunkProviderServer.provideChunk -> loadChunk
22,7 %      ChunkIOExecutor.syncChunkLoad  (leitura síncrona da região + descompressão + NBT)
11,4 %      AnvilChunkLoader.chunkExists -> RegionFile.<init>
```

Dos 35,5 % do tempo gasto em `ChunkProviderServer.loadChunk`, **34,1 %** vêm dessa única chamada. O resto (Thaumcraft, estruturas,
baús, etc.) soma ~1,4 %. Contagem de chunks no sampler: 81.480 → 101.608 → 112.221 → 112.204 → 111.541 em 5 min [medido].

### 3.1 O código (ChromatiCraft V33a, decompilado do jar do modpack) [código]

`TileEntityCrystalPylon.updateEntity`, lado servidor, quando `energy == capacity` (todo pylon fica cheio sem jogadores drenando):

```java
Coordinate c1 = new Coordinate(this).offset(0, -9, 0);
Coordinate c = c1.offset(getRandomPlusMinus(0, 12), getRandomPlusMinus(0, 2), getRandomPlusMinus(0, 12));
if (c.getTaxicabDistanceTo(c1) >= 4 && !structure.hasBlock(c) && c.getBlock(world) == PYLONSTRUCT) { ... }
```

Um bloco aleatório a até 12 blocos, **a cada tick**. `World.getBlock` em chunk não carregado carrega (ou gera) o chunk. Outras leituras
fora do chunk no mesmo método: bloco aleatório da estrutura (`getBlockKey`), checagem de neve, `isBlockEncased` (3×3×3),
`reloadEncrusted` (tick 0), crescimento de cristal incrustado, pylons ligados (`WorldLocation.getTileEntity()`).

### 3.2 A conta fecha [estimativa a partir de dados medidos]

A janela de ±12 blocos (25 posições) cobre 2 chunks por eixo quando o pylon está a < 4 ou ≥ 12 blocos da borda do chunk, e 3 chunks
caso contrário: média 2,5 por eixo → **6,25 chunks por pylon**. 19.087 pylons × 6,25 ≈ **119.300 chunks**. O heap tinha **124.499**.

### 3.3 Por que o chunk GC do Crucible não resolve [código + medido]

O Crucible tem GC de chunks (`CraftWorld.processChunkGC`, a cada `chunk-gc.period-in-ticks`, padrão 600 ticks) e descarrega no máximo
**100 chunks por tick por mundo** (`ChunkProviderServer.unloadQueuedChunks`). A 2–5 TPS, 600 ticks são 2–5 minutos e esvaziar 112 mil
chunks leva mais de 1.100 ticks; enquanto isso, cada pylon ainda carregado recarrega os vizinhos que acabaram de sair. No servidor de
teste, a 20 TPS e com 46 pylons, o GC venceu em ~400 ticks; com GC desligado (simulando o GC que não acompanha), os chunks ficaram
carregados para sempre (E4a).

## 4. Por que todos os pylons estão carregados: dois carregadores no boot

Os chunks dos pylons em si precisam estar carregados para o pylon "tickar". Com 0 jogadores, quem os carrega:

1. **`CrystalNetworker.load`** [código]: chamado uma vez, de dentro do primeiro `addTile`. Passa a lista salva (`crystalnet.dat`) a
   `TileEntityCache.readFromNBT`, que faz `loc.getTileEntity()` para cada tile já registrado → carrega o chunk de cada um.
2. **`PylonGenerator.loadPylonLocations`** [código]: chamado logo em seguida (cache `pylonloc.dat`). Valida cada pylon com
   `validateCachedLocation` → `loc.getTileEntity()` → carrega o chunk de **cada pylon do cache**. 19.087 `PylonEntry` no heap
   ≈ 19.122 `TileEntityCrystalPylon` carregados [medido].

No servidor de teste, só bloquear o item 1 não basta: o item 2 carregou os 42 pylons logo depois (E2, primeira versão do patch).

Detalhe adicional [código, confirmado no E7a]: `addTile` registra o tile **antes** de chamar `load`, e `readFromNBT` começa com
`data.clear()`; o primeiro tile registrado só volta para a rede se estiver no arquivo. Sem patch, com a lista vazia, 46 pylons
carregados geraram só 45 tiles na rede.

## 5. O patch anterior (`akashic-crystalnet-lazyload` 1.0.1)

* No seu `fml-server-latest.log` (boot 14:02): o mod está na lista e a classe `CrystalNetBridge` foi carregada, mas não há **nenhuma**
  linha `[AkashicCrystalNet]`; 30 s depois, o watchdog registrou um stall de 30 s [medido].
* No servidor de teste: o mixin dele **foi aplicado** (confirmado com `-Dmixin.debug.export=true`: os 4 handlers estão no
  `CrystalNetworker`), as classes `CrystalNetBridge` e `LinkCheck` carregam, mas o filtro nunca atua e nada é logado; o boot carregou
  os 46 chunks de pylon, idêntico a sem patch (E1, E1b, E1c) [medido]. A causa interna exata do silêncio **não foi isolada**: o jar está
  sendo substituído e o desenho dele já não cobria os itens 1 (tick) e 4.2 (`pylonloc`).

## 6. Experimentos (servidor de teste real)

Ambiente: Crucible `1.7.10-staging-67b53034` (mesmo build do servidor), Java 21, UniMixins 0.3.1, lwjgl3ify 3.0.31, GTNHLib 0.11.51,
FalsePatternLib 1.12.2, DragonAPI V33b-ForbiddenFix1, ChromatiCraft V33a (configs de pylon iguais às do modpack), Akashic Forbidden
Integrity Fix. Mundo novo com 40 pylons gerados pelo `PylonGenerator.generatePylon` do próprio ChromatiCraft (a 2 blocos da borda do
chunk) + 6 pylons naturais, todos com energia cheia; 0 jogadores. Saídas brutas em `test/results/`.

| Exp. | Configuração | Pylons carregados após boot | Chunks (tick 200) | Carregamentos no boot | Observação |
|---|---|---|---|---|---|
| E0 | sem patch | 46 | 792 | 185 | GC do Crucible descarrega em ~400 ticks (20 TPS) |
| E1 | só o jar antigo | 46 | 792 | 195 | nenhuma linha de log do jar antigo |
| E2b | patch | **4** | **276** | **1** | os 3 filtros logam; 115 carregamentos bloqueados |
| E3 | patch + ticket 5×5 em um pylon | — | — | — | pylon registra na rede, guard não interfere, recarga de energia OK (0 → 604 em 600 ticks); ticket só no chunk do pylon: bloqueios sobem, carregamentos ficam em 0 |
| E4a | sem patch, chunk GC desligado | 46 | 792 (fixo) | 167 | chunks dos pylons nunca saem |
| E4b | patch, chunk GC desligado | 4 | 625 (spawn) | 0 | |
| E5 | patch + `-Dakashic.pylonguard=false` | 46 | — | — | nenhum mixin aplicado (comportamento original) |
| E6b | patch + jar antigo juntos | 4 | 276 | 1 | patch atua; aviso para remover o antigo |
| E7a/b | lista `crystalnet` vazia | 46 / 4 | — | — | sem patch: 45 tiles na rede para 46 pylons; com patch: "1 in-memory registration kept", nenhum tile perdido |
| E8a/b/c | boot 1, boot 2, rollback | 4 / 4 / 46 | 275 / 625 / 792 | 0 / 0 / 167 | rollback restaura o original; `crystalnet.dat` volta de 4 para 46 entradas |
| E9 | 1.0.0 (`5575f205…`) + jar antigo | 4 | 625 | 0 | teste de fumaça do 1.0.0 (substituído pelo 1.0.1, ver 6.1) |

Erros no log: o conjunto de linhas `ERROR` com o patch é idêntico ao da linha de base (E0 × E8a). O erro ocasional
`Failed to generate a 4x4 puzzle` (thread de estruturas da dimensão do ChromatiCraft) aparece também sem o patch (E5, E7a, boots anteriores).

Builds usados: E2b/E3/E4b/E5 rodaram com um build anterior em que o loader pulava o filtro de boot quando o jar antigo estava presente;
E6b/E7b/E8 com o build `29a9c9a0…`; o 1.0.0 (`5575f205…`) difere deste só no texto de um aviso de log e passou pelo E9.
Nesses experimentos o mixin do pylon e o do `PylonGenerator` são idênticos aos do 1.0.1; o do `CrystalNetworker` mudou no
1.0.1 (seção 6.1), que foi testado em E10e, E11c, E12c e R1–R5. Jar entregue: **1.0.1 (`9bf45e50…`)**. O build é
determinístico (mesmo SHA-256 em builds repetidos, com ou sem o dump de classes de runtime).

## 6.1 Rede de cristal e progressão (1.0.0 → 1.0.1)

Pergunta verificada: o ChromatiCraft continua funcional e progressivo com o patch?

* [código] O `PylonFinder` (caminho receptor → repetidores → pylon, usado pela mesa de conjuração, máquinas, carregadores)
  procura transmissores com `CrystalNetworker.getTransmittersTo`, que só enxerga tiles **registrados**. Sem patch, o boot
  registra todos; depois o GC do Crucible descarrega os chunks, mas os tiles continuam no mapa e o `CrystalFlow` recarrega
  o chunk do pylon quando transfere energia.
* **O 1.0.0 quebrava isso** [medido]: carregador (`TileEntityCrystalCharger`, receptor real, alcance 20) a 20 blocos /
  2 chunks de um pylon com o chunk descarregado:

| Exp. | Mundo | Configuração | Energia no carregador | Carregamentos no boot | Tiles resolvidos sob demanda |
|---|---|---|---|---|---|
| E10c | 46 tiles | sem patch | **120.000** | 197 | — |
| E10d | 46 tiles | 1.0.0 | **0** | 0 | — |
| E10e | 46 tiles | **1.0.1** | **120.000** | 0 | 42 (rede ≤100 tiles: o mod faz uma consulta global) |
| E11a | 141 tiles | sem patch | **120.000** | 675 | — |
| E11b | 141 tiles | 1.0.0 | **0** | 0 | — |
| E11c | 141 tiles | **1.0.1** | **120.000** | 0 | **1** (só o pylon no alcance) |
| E12a | 142 tiles, cadeia pylon → **repetidor** → carregador | sem patch | **120.000** | 546+ | — |
| E12b | idem | 1.0.0 | **0** | 0 | — |
| E12c | idem | **1.0.1** | **120.000** | 0 | **2** (o repetidor e o pylon) |

  (E10a, com o carregador a 15 blocos no chunk vizinho, não isolava o caso: o chunk do pylon foi carregado pela vizinhança do
  chunk forçado e o pylon se registrou sozinho.)
* **Correção do 1.0.1** [código + medido]: tiles em chunks descarregados vão para um registro adiado
  (`DeferredNetworkTiles`), gravado em todo save (o `crystalnet.dat` ficou com 46 → 46 entradas em dois boots seguidos; no
  1.0.0 caía para 4) e resolvido sob demanda em todas as consultas de alcance do `CrystalNetworker` (`getTransmittersTo`,
  `getNearbyReceivers`, `getNearTilesOfType`, `getNearestTileOfType`, `getNearbyPylons`, `getAllNearbyPylons`) e na única
  consulta global (`getAllSourcesFor`, chamada pelo mod só para redes de até 100 tiles). `size()` inclui os adiados, para o
  atalho "rede com mais de 100 tiles" do `PylonFinder` funcionar como sem patch (no servidor real a rede tem 19 mil+ tiles).
* [código] Gatilhos de progressão nas classes alteradas: `PYLON`, `LINK`, `POWERCRYSTAL`, `TURBOCHARGE`, `RUNEUSE`, `CTM`,
  `ALLCOLORS` ficam em `onUsingWandTick` / `canPlayerWandPylon` / `charge` (jogador presente; não alterados). `USEENERGY`
  dispara quando uma requisição de energia dá certo — o caso provado acima.
* Bateria de regressão do 1.0.1 (R1–R5, `test/results/`): boot 1 e 2 sem carregamentos e sem perda de dados, rollback
  íntegro, lista vazia ("1 in-memory registrations kept"), convivência com o jar antigo; linhas `ERROR` idênticas à base.

## 7. O que não está provado (e como confirmar)

* O ganho em produção é **estimativa**: a escala do teste é 46 pylons, não 19 mil. A conta da seção 3.2 e o sampler indicam que os
  pylons explicam quase todos os 124 mil chunks; confirme com `/spark heapsummary` e `/spark tps` 10–15 min após o boot.
* Testado com um subconjunto dos ~280 mods do servidor. Outro mod com `@Redirect` nas mesmas chamadas faria o boot falhar (falha segura).
* A rede foi testada com receptor real (carregador) direto do pylon e por uma cadeia com um repetidor real (estrutura de
  runa + pilares). Não foram testados a mesa de conjuração, Skypeaters, Broadcasters ou redes de jogadores com dezenas de
  repetidores; o mecanismo é o mesmo (`getTransmittersTo` em cada nó da busca), mas vale conferir uma rede real de um
  jogador logo após o primeiro restart com o patch.
* Se, com o patch, o número de chunks/entidades continuar alto, existe outra fonte; o próximo passo seria um novo sampler com o patch ativo.
