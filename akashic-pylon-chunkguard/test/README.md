# test/ — reprodução dos experimentos (NÃO vai para produção)

* `harness/` — mod **só de teste** `akashic_pg_harness`. Comandos de console:
  * `akpg place <n> <espaçamento>` gera pylons com `PylonGenerator.generatePylon` do próprio ChromatiCraft e enche a energia;
    `akpg fill`, `akpg stats`, `akpg pylons`, `akpg setenergy <x> <z> <valor>`, `akpg ticket <x> <z> <raio>` (força chunks);
  * rede: `akpg charger <x> <y> <z>` (coloca um Crystal Charger), `akpg chargerstat`, `akpg repeater ...`;
  * cristal incrustado: `akpg encrusted <x> <z>` (conta os tiles num raio de 16) e `akpg grow <x> <y> <z> <n>` (chama `n` vezes o
    `tryGrowEncrusted` privado do próprio pylon, com os mixins aplicados, e loga `counted=` e `chunkLoadsDuring=`);
  * cache `pylonloc`: `akpg pyloncache <x> <z>` (entrada do `PylonGenerator` naquela coluna) e `akpg removepylon <x> <y> <z>`
    (apaga o bloco como uma ferramenta de edição faria, sem avisar o mod).

  A cada 200 ticks loga
  `[AKPG] tick=.. mspt=.. loaded=<chunks> loads=<ChunkEvent.Load na janela> pylons=<pylons carregados> net=<tiles da rede> prevented=<bloqueios do patch> resolved=<tiles adiados resolvidos>`.
  Build: `FORGE_SRG=... VANILLA_SRG=... MODS=... [RT_CLASSES=...] ./build-harness.sh` (as pastas SRG saem de `tools/srgremap`, ver `tools/build.sh`).
* `server-scripts/` — copie para a pasta do servidor de teste (Crucible + `java9args.txt`). `run.sh` sobe o servidor com o console num FIFO,
  `cmd.sh` envia comandos, `stop.sh` para e espera o processo, `experiment*.sh <nome> <segundos> [args JVM]` restaura `../world-template`,
  sobe, mede e para (exigem `NBTLOCS=<caminho de tools/nbtlocs.py>`). `regression101.sh`, `audit102.sh` e `audit102-cfix.sh` são as
  baterias do 1.0.1 e da auditoria do 1.0.2.
* `tools/nbtlocs.py` lê `crystalnet.dat`; `tools/empty_crystalnet.py` gera um com a lista vazia (experimento E7).
* `results/` — linhas relevantes de cada experimento (E0–E12, R1–R5, auditoria do 1.0.2: T4, T5, E11d, E12d, R1b–R5b, C1–C3).
  A tabela-resumo está em `../docs/DIAGNOSTICO.md`, seções 6, 6.1 e 6.2.
