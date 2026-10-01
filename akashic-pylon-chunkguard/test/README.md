# test/ — reprodução dos experimentos (NÃO vai para produção)

* `harness/` — mod **só de teste** `akashic_pg_harness`. Comandos de console: `akpg place <n> <espaçamento>` (gera pylons com
  `PylonGenerator.generatePylon` do próprio ChromatiCraft e enche a energia), `akpg fill`, `akpg stats`, `akpg pylons`,
  `akpg setenergy <x> <z> <valor>`, `akpg ticket <x> <z> <raio>`. A cada 200 ticks loga
  `[AKPG] tick=.. mspt=.. loaded=<chunks> loads=<ChunkEvent.Load na janela> pylons=<pylons carregados> net=<tiles da rede> prevented=<bloqueios do patch>`.
  Build: `FORGE_SRG=... VANILLA_SRG=... MODS=... [RT_CLASSES=...] ./build-harness.sh` (as pastas SRG saem de `tools/srgremap`, ver `tools/build.sh`).
* `server-scripts/` — copie para a pasta do servidor de teste (Crucible + `java9args.txt`). `run.sh` sobe o servidor com o console num FIFO,
  `cmd.sh` envia comandos, `stop.sh` para e espera o processo, `experiment*.sh <nome> <segundos> [args JVM]` restaura `../world-template`,
  sobe, mede e para (exigem `NBTLOCS=<caminho de tools/nbtlocs.py>`).
* `tools/nbtlocs.py` lê `crystalnet.dat`; `tools/empty_crystalnet.py` gera um com a lista vazia (experimento E7).
* `results/` — linhas relevantes de cada experimento (E0–E9). A tabela-resumo está em `../docs/DIAGNOSTICO.md`, seção 6.
