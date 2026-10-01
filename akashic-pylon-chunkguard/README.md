# akashic-pylon-chunkguard

Correção **server-side** para o lag do servidor Crucible 1.7.10 (modpack TEOF 3.2): os **Crystal Pylons do ChromatiCraft V33a**
carregavam, sozinhos e continuamente, ~120 mil chunks do mundo sem nenhum jogador online. Isso enchia o heap (16,8 de 17 GB),
causava Full GCs de ~3,4 s a cada ~11 s e derrubava o TPS para 2–5 (lag, comandos com atraso).

Diagnóstico completo, com números e evidências: [`docs/DIAGNOSTICO.md`](docs/DIAGNOSTICO.md).

**Jar:** `dist/akashic-pylon-chunkguard-1.0.0.jar` (SHA-256 em `dist/*.sha256`). Java 8, só no **servidor**; clientes não precisam dele.

## O que ele corrige (3 pontos, todos medidos em um Crucible real)

| # | Onde (ChromatiCraft V33a) | O que acontecia | O que o patch faz |
|---|---|---|---|
| 1 | `TileEntityCrystalPylon.updateEntity` (tick) | Todo pylon com energia cheia (todos, sem jogadores) lia **a cada tick** um bloco aleatório a até 12 blocos de distância, além da estrutura e da vizinhança. Ler bloco de chunk descarregado = carregar o chunk do disco no thread principal. Média de 6,25 chunks por pylon × 19.087 pylons ≈ **119 mil chunks** (o heap tinha 124.499). No profiler: **34 % do thread principal**. | Se o chunk alvo não está carregado, a leitura responde "bloco inerte" e o pylon simplesmente não faz aquela ação naquele tick. Com a área carregada (jogador perto, chunkloader), roda o código original, sem alteração. |
| 2 | `CrystalNetworker.load` (boot) | Na primeira leitura da rede, chamava `getTileEntity()` para **todo tile de rede já salvo** → carregava o chunk de cada um (stall de 30 s+ no boot). | Só resolve os tiles cujo chunk já está carregado. Os demais se registram sozinhos quando o chunk carregar (comportamento normal do mod: `onFirstTick → addTile`). Também preserva os registros já feitos em memória, que o `data.clear()` original apagava. |
| 3 | `PylonGenerator.loadPylonLocations` (boot) | Validava cada pylon do cache `pylonloc` com `getTileEntity()` → **carregava o chunk de todos os pylons do cache** (19.087). Este segundo carregador nunca tinha sido identificado. | Entradas em chunks descarregados são mantidas sem validar (o cache é gravado a partir do cache vivo, e pylon quebrado já sai dele na hora da quebra). Entradas em chunks carregados são validadas como antes. |

Resultado no servidor de teste (mesmo mundo, 46 pylons, 0 jogadores — ver `test/results/`):

| | sem patch | com patch |
|---|---|---|
| Pylons carregados após o boot | 46 | 4 (só os do spawn) |
| Chunks carregados (tick 200) | 792 | 275–276 |
| Chunks carregados por causa dos pylons no boot | 167–185 | 0–1 |
| Carregamentos bloqueados pelo guard | — | ~110–130 em 2 min (pylons do spawn) |

## Instalação

1. **Backup** do mundo (no mínimo `world/data/crystalnet.dat` e `world/data/pylonloc.dat`; ideal: o mundo todo).
2. **Remova** `akashic-crystalnet-lazyload-*.jar` de `mods/`. Ele é substituído por este (ver "Patch anterior" abaixo).
   Se esquecer, os dois convivem sem problema e este avisa no log, mas remova.
3. Copie `akashic-pylon-chunkguard-1.0.0.jar` para `mods/` **do servidor** (requer o UniMixins que já está no servidor).
4. Reinicie.

## Como confirmar que está funcionando (log do servidor)

```
[AkashicPylonGuard]: Crystal pylon chunk guard: applying [TileEntityCrystalPylonMixin, PylonGeneratorMixin, CrystalNetworkerMixin].
[AkashicPylonGuard]: Crystal network load: N saved tile locations; K in loaded chunks resolved now; D in unloaded chunks NOT force-loaded ...
[AkashicPylonGuard]: Pylon location cache load: V pylons in loaded chunks validated; P pylons in unloaded chunks kept without force-loading their chunk.
[AkashicPylonGuard]: Prevented the first chunk load by a crystal pylon (...)
[AkashicPylonGuard]: Last 10 minutes: prevented X chunk loads by crystal pylons (Y since start).
```

* A linha `Crystal network load` só aparece quando há algo a filtrar (num segundo boot ela costuma não aparecer: o arquivo já só tem tiles em chunks carregados).
* Esperado em produção, ~10–15 min após o boot sem jogadores: `/spark heapsummary` com `TileEntityCrystalPylon`, `net.minecraft.world.chunk.Chunk`,
  `Coordinate`, `BlockKey`, `byte[]` muito abaixo de 19 mil / 124 mil / 21 M / 9 M / 10 GB; `/spark tps` perto de 20 e sem Full GC em sequência.
  (Esses números de produção são **estimativa** baseada no diagnóstico; o teste foi em escala menor. Confirme com o spark.)

## Mudanças de comportamento (intencionais)

* Pylon cuja vizinhança está descarregada não cresce cristais incrustados, não limpa neve da estrutura e não faz a checagem
  de "jarro" fora do próprio chunk naquele momento. Tudo volta ao normal quando a área carrega.
* Pylons ligados por Pylon Link só dividem energia com os ligados que estão carregados (os Pylon Links já mantêm seus chunks carregados por ticket).
* Tiles da rede de cristal em chunks descarregados não são "pré-carregados" no boot; entram na rede quando o chunk carrega.
  Máquinas que puxam energia de um pylon distante precisam que a área dele esteja carregada (chunkloader ou a opção do mod
  "Pylons Chunkload Selves Once Used", que já está ligada no seu config) — o mesmo que já acontecia sempre que o Crucible descarregava esses chunks.
* `crystalnet.dat` passa a listar só os tiles registrados desde o boot (se recompõe sozinho; voltar sem o patch restaura tudo — testado).

## Desligar / reverter

* `-Dakashic.pylonguard=false` nas flags da JVM: nenhum mixin é aplicado (comportamento original).
* `-Dakashic.pylonguard.bootfilter=false`: desliga só os filtros de boot (itens 2 e 3); o guard do tick continua.
* Remover o jar: volta ao original. Testado: o ChromatiCraft recarrega e re-registra tudo no boot seguinte (`crystalnet.dat` voltou de 4 para 46 entradas).
  O FML mostra o aviso padrão `This world was saved with mod akashic_pylon_chunkguard which appears to be missing` — esperado ao remover qualquer mod.

## Patch anterior (`akashic-crystalnet-lazyload` 1.0.1)

Testado no mesmo servidor real: o mixin dele **é aplicado**, mas o filtro **nunca atuou** (nenhuma linha de log, igual ao seu `latest.log`
de produção, e os 46 chunks de pylon foram carregados no boot exatamente como sem patch). Mesmo funcionando, ele só tratava o item 2:
o item 3 (`pylonloc`) carregaria todos os pylons de qualquer forma, e o item 1 (o maior) não era tratado. Detalhes em `docs/DIAGNOSTICO.md`.

## Como foi construído e testado (sem stubs)

* `tools/build.sh`: `javac --release 8 -Werror` direto contra **classes reais**: as classes Minecraft que o Crucible carrega em runtime
  (`RFB_CLASS_DUMP`), o Forge/FML do `server.jar` do Crucible remapeado para SRG com a **tabela oficial do FML**
  (`deobfuscation_data-1.7.10.lzma`, via `tools/srgremap`), e os jars reais de ChromatiCraft, DragonAPI e UniMixins.
  Os nomes SRG usados no código foram conferidos contra o MCP `stable_12`. Jar determinístico (`jar --date`).
* `test/`: mod **só de teste** (`akpg-harness`, nunca instalar em produção) que gera pylons com o gerador do próprio ChromatiCraft,
  enche a energia e mede chunks; scripts do servidor de teste; resultados brutos em `test/results/`.
* Servidor de teste: Crucible `1.7.10-staging-67b53034` (o mesmo build do seu servidor), Java 21, UniMixins 0.3.1, lwjgl3ify 3.0.31,
  GTNHLib 0.11.51, FalsePatternLib 1.12.2, DragonAPI V33b-ForbiddenFix1, ChromatiCraft V33a, Akashic Forbidden Integrity Fix.
  Dois patchers ASM do DragonAPI (`SPLASHPOTIONEVENT`, `CHUNKGENERATIONEVENT`) foram desligados **só no teste**, porque sem os
  mods de compatibilidade do seu servidor (ex.: AkashicChunkBridge) eles impediam o boot; não têm relação com pylons.

## Limites conhecidos

* Testado com um subconjunto dos ~280 mods. Se outro mod tiver um `@Redirect` nas mesmas chamadas do pylon, o servidor **falha no boot**
  com erro de Mixin (falha alta e segura, sem tocar no mundo): remova o jar e me mande o log.
* Na primeira vez que um pylon carrega, o ChromatiCraft monta a estrutura dele e pode ler 1–2 chunks vizinhos (visto no teste: 3 carregamentos).
  É uma vez por carregamento e só acontece quando o chunk do pylon foi carregado por outro motivo.
* Itens no chão (88 mil) e animais (156 mil entidades) estavam nos chunks carregados pelos pylons; devem cair junto. Se continuarem altos
  depois do patch, a causa é outra e precisa de novo profiling.
