# akashic-pylon-chunkguard

Correção **server-side** para o lag do servidor Crucible 1.7.10 (modpack TEOF 3.2): os **Crystal Pylons do ChromatiCraft V33a**
carregavam, sozinhos e continuamente, ~120 mil chunks do mundo sem nenhum jogador online. Isso enchia o heap (16,8 de 17 GB),
causava Full GCs de ~3,4 s a cada ~11 s e derrubava o TPS para 2–5 (lag, comandos com atraso).

Diagnóstico completo, com números e evidências: [`docs/DIAGNOSTICO.md`](docs/DIAGNOSTICO.md).

**Jar:** `dist/akashic-pylon-chunkguard-1.0.2.jar` (SHA-256 em `dist/*.sha256`). Java 8, só no **servidor**; clientes não precisam dele.

> **Use o 1.0.2.** O 1.0.0 resolvia o lag mas quebrava redes de cristal que passam por áreas descarregadas (provado em E10/E11,
> corrigido no 1.0.1). O 1.0.2 é o resultado da auditoria do 1.0.1 (`docs/DIAGNOSTICO.md`, seção 6.2). Ele aplica a regra
> "**adiar, nunca alterar**" a tudo o que o patch toca: o crescimento de cristal incrustado é tudo-ou-nada, e a contagem do
> limite de 6 cristais volta a ser a original. A partilha de energia entre pylons ligados também volta a ser a original. As
> entradas do cache `pylonloc` passam a ser validadas quando o chunk delas carrega.

## Regra do patch

Uma ação do pylon que precisaria **carregar um chunk** é apenas **adiada**: naquele tick ela não acontece, como se o sorteio
aleatório do próprio mod tivesse caído em outro lugar. Quando a área está carregada (jogador perto, chunkloader, Pylon Link),
roda o código original do ChromatiCraft, sem nenhuma alteração. Nada que o mod calcula (energia, crescimento, limites,
progressão) é recalculado pelo patch.

## O que ele corrige (3 pontos, todos medidos em um Crucible real)

| # | Onde (ChromatiCraft V33a) | O que acontecia | O que o patch faz |
|---|---|---|---|
| 1 | `TileEntityCrystalPylon.updateEntity` (tick) | Todo pylon com energia cheia (todos, sem jogadores) lia **a cada tick** um bloco aleatório a até 12 blocos de distância, além de um bloco da estrutura. Ler bloco de chunk descarregado = carregar o chunk do disco no thread principal. Média de 6,25 chunks por pylon × 19.087 pylons ≈ **119 mil chunks** (o heap tinha 124.499). No profiler: **34 % do thread principal**. | Se o chunk não está carregado, a ação daquele tick é adiada: a varredura de 12 blocos e a limpeza de neve não acontecem naquele tick. O crescimento de cristal incrustado é tudo-ou-nada: só roda (inteiro, código original) se a origem e a coluna 3×3 do alvo estão carregadas. A checagem de "jarro" (3×3×3) espera a área carregar. |
| 2 | `CrystalNetworker.load` (boot) | Na primeira leitura da rede, chamava `getTileEntity()` para **todo tile de rede já salvo** → carregava o chunk de cada um (stall de 30 s+ no boot). | Só resolve no boot os tiles cujo chunk já está carregado. Os demais ficam num registro **adiado**: continuam sendo gravados no save e são resolvidos **sob demanda** quando uma busca da rede (caminho até a mesa/máquina, consulta de vizinhança) chega ao alcance deles — o chunk é carregado nesse momento, como o próprio `CrystalFlow` do mod faz ao transferir energia. Também preserva o primeiro tile registrado, que o `data.clear()` original apagava. |
| 3 | `PylonGenerator.loadPylonLocations` (boot) | Validava cada pylon do cache `pylonloc` com `getTileEntity()` → **carregava o chunk de todos os pylons do cache** (19.087). Este segundo carregador nunca tinha sido identificado. | Entradas em chunks carregados são validadas como antes. As demais são mantidas e **validadas quando o chunk delas carrega** (por qualquer motivo), com a mesma regra do mod: se ali não houver mais um pylon daquela cor, a entrada é removida, como o original faria no boot. |

O que o patch **não** toca (comportamento original, conferido no código):

* recarga de energia, ataques, interação com a varinha e todos os gatilhos de progressão (`PYLON`, `LINK`, `POWERCRYSTAL`,
  `TURBOCHARGE`, `RUNEUSE`, `CTM`, `ALLCOLORS`, `USEENERGY`);
* partilha de energia entre pylons ligados: os Pylon Links mantêm seus 3×3 chunks carregados por ticket do DragonAPI;
* `reloadEncrusted`, a contagem dos cristais incrustados existentes para o limite de 6. Roda uma vez por carregamento do pylon
  e lê só a vizinhança da estrutura.

Resultado no servidor de teste (mesmo mundo, 46 pylons, 0 jogadores — ver `test/results/`):

| | sem patch | com patch |
|---|---|---|
| Pylons carregados após o boot | 46 | 4 (só os do spawn) |
| Chunks carregados (tick 200) | 792 | 275–276 |
| Chunks carregados por causa dos pylons no boot | 167–185 | 0–1 |
| Carregamentos bloqueados pelo guard | — | ~110–130 em 2 min (pylons do spawn) |

## Instalação

1. **Backup** do mundo (no mínimo `world/data/crystalnet.dat` e `world/data/pylonloc.dat`; ideal: o mundo todo).
2. **Remova** de `mods/` qualquer `akashic-pylon-chunkguard-1.0.0.jar`/`-1.0.1.jar` e o `akashic-crystalnet-lazyload-*.jar`.
   O lazyload é substituído por este (ver "Patch anterior" abaixo). Se esquecer o lazyload, os dois convivem sem problema e
   este avisa no log, mas remova.
3. Copie `akashic-pylon-chunkguard-1.0.2.jar` para `mods/` **do servidor** (requer o UniMixins que já está no servidor).
4. Reinicie.

## Como confirmar que está funcionando (log do servidor)

```
[AkashicPylonGuard]: Crystal pylon chunk guard: applying [TileEntityCrystalPylonMixin, PylonGeneratorMixin, CrystalNetworkerMixin].
[AkashicPylonGuard]: Crystal network load: N saved tile locations; K in loaded chunks resolved now; D in unloaded chunks deferred (NOT force-loaded; resolved on demand ...) ...
[AkashicPylonGuard]: Pylon location cache load: V pylons in loaded chunks validated; P pylons in unloaded chunks kept without force-loading their chunk (validated when their chunk loads).
[AkashicPylonGuard]: Prevented the first chunk load by a crystal pylon (...)
[AkashicPylonGuard]: Last 10 minutes: prevented X chunk loads by crystal pylons (Y since start).
[AkashicPylonGuard]: Crystal network: resolved a deferred network tile on demand (...)        <- quando alguém usa a rede
[AkashicPylonGuard]: Last 10 minutes: resolved R deferred crystal network tiles on demand (...)
[AkashicPylonGuard]: Pylon location cache: removed stale entry ... (no such pylon when its chunk loaded; ...)  <- raro: pylon apagado por ferramenta
```

* Esperado em produção, ~10–15 min após o boot sem jogadores: `/spark heapsummary` com `TileEntityCrystalPylon`, `net.minecraft.world.chunk.Chunk`,
  `Coordinate`, `BlockKey`, `byte[]` muito abaixo de 19 mil / 124 mil / 21 M / 9 M / 10 GB; `/spark tps` perto de 20 e sem Full GC em sequência.
  (Esses números de produção são **estimativa** baseada no diagnóstico; o teste foi em escala menor. Confirme com o spark.)

## O que muda no jogo (só o momento, nunca o resultado)

* Pylon cuja vizinhança está descarregada adia o crescimento de cristais incrustados, a limpeza de neve da estrutura e a
  checagem de "jarro". Tudo roda normalmente, com o código original, quando a área carrega. Com um jogador perto, ou com um
  Pylon Link, a área está carregada e nada muda.
* Tiles da rede de cristal em chunks descarregados não são carregados no boot; são carregados quando a rede precisa deles
  (alguém pede energia por um caminho que passa por eles). A rede encontra os mesmos pylons/repetidores que sem o patch.
  A seleção usa a mesma métrica de distância do ChromatiCraft. Testado: um carregador a 2 chunks de um pylon descarregado e
  um carregador alimentado por um repetidor real receberam 120.000 de energia, igual a sem patch. Só os tiles do caminho
  foram resolvidos: 1 e 2.
* `crystalnet.dat` mantém todas as entradas (testado em dois boots seguidos: 46 → 46).
* Cache `pylonloc`: uma entrada de pylon que sumiu sem o mod perceber (ex.: apagado por ferramenta de edição de mundo) é
  removida quando o chunk dela carrega, não no boot. Um jogador que entrou antes disso ainda vê essa entrada no HUD do
  Pylon Finder até relogar, o mesmo que o próprio ChromatiCraft faz quando remove uma entrada (`removeCachedPylon`).

## Desligar / reverter

* `-Dakashic.pylonguard=false` nas flags da JVM: nenhum mixin é aplicado (comportamento original).
* `-Dakashic.pylonguard.bootfilter=false`: desliga só os filtros de boot (itens 2 e 3); o guard do tick continua.
* Remover o jar: volta ao original. Testado: o ChromatiCraft volta a carregar e registrar tudo no boot (dados íntegros: 46 entradas).
  O FML mostra o aviso padrão `This world was saved with mod akashic_pylon_chunkguard which appears to be missing` — esperado ao remover qualquer mod.

## Compatibilidade com o modpack

* Os ~280 jars do TEOF 3.2 foram varridos atrás de referências a `CrystalNetworker`, `TileEntityCrystalPylon` e `PylonGenerator`.
  Fora o próprio ChromatiCraft, só o **ChromatiFixes 1.14.5** mexe nessas classes. Os mixins dele (`getNearestPylonSpawn`,
  acessores do `CrystalNetworker`, `checkInterfere` do Broadcaster, partículas só no cliente) não colidem com os deste patch.
  A chamada do Broadcaster passa pela consulta que este patch já cobre. Testado junto, no servidor real (C1–C3, seção 6.2 do diagnóstico).

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
  enche a energia, mede chunks e chama o código do próprio ChromatiCraft (ex.: `tryGrowEncrusted`); scripts do servidor de teste;
  resultados brutos em `test/results/`.
* Servidor de teste: Crucible `1.7.10-staging-67b53034` (o mesmo build do seu servidor), Java 21, UniMixins 0.3.1, lwjgl3ify 3.0.31,
  GTNHLib 0.11.51, FalsePatternLib 1.12.2, DragonAPI V33b-ForbiddenFix1, ChromatiCraft V33a, Akashic Forbidden Integrity Fix
  (e ChromatiFixes 1.14.5 nos testes C1–C3).
  Dois patchers ASM do DragonAPI (`SPLASHPOTIONEVENT`, `CHUNKGENERATIONEVENT`) foram desligados **só no teste**, porque sem os
  mods de compatibilidade do seu servidor (ex.: AkashicChunkBridge) eles impediam o boot; não têm relação com pylons.

## Limites conhecidos

* Testado com um subconjunto dos ~280 mods (os que tocam nas mesmas classes estão incluídos, ver "Compatibilidade"). Se uma
  versão diferente do ChromatiCraft/DragonAPI mudar as chamadas alteradas, o servidor **falha no boot** com erro de Mixin
  (os `require` contam as chamadas exatas do V33a). É uma falha alta e segura, sem tocar no mundo: remova o jar e me mande o log.
* Na primeira vez que um pylon carrega, o ChromatiCraft monta a estrutura dele e conta os cristais existentes (`reloadEncrusted`,
  não alterado). Isso pode ler 1–2 chunks vizinhos (visto no teste: 3 carregamentos). É uma vez por carregamento e só acontece
  quando o chunk do pylon foi carregado por outro motivo.
* Itens no chão (88 mil) e animais (156 mil entidades) estavam nos chunks carregados pelos pylons; devem cair junto. Se continuarem altos
  depois do patch, a causa é outra e precisa de novo profiling.
