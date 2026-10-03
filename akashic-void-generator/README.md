# AkashicVoidGenerator

Gerador de mundo void para **Crucible 1.7.10 + EndlessIDs**. Substitui o `VoidGenerator 1.0`
(Bogdacutu, 2011), que derruba a geração de chunks nesse ambiente.

O terreno gerado é o mesmo do VoidGenerator: um único bloco de bedrock em `(0, 64, 0)` e o resto
vazio. O spawn fixo fica em `(0.5, 65, 0.5)`, em cima da bedrock, então o Multiverse aceita o spawn
sem precisar "ajustar".

## Por que o VoidGenerator antigo quebra

- O VoidGenerator só implementa a API anterior à 1.2, `byte[] generate(World, Random, int, int)`.
- Com essa API, o `CustomChunkGenerator` do Crucible preenche o chunk escrevendo direto em
  `ExtendedBlockStorage.getBlockLSBArray()` (`CustomChunkGenerator.java:123`).
- O EndlessIDs 1.7.4 bloqueia esse acesso de propósito (`ExtendedBlockStorageMixin.crashLSBArray`)
  e lança `UnsupportedOperationException` para evitar corromper o mundo.
- Só o chunk (0,0) tem bloco (a bedrock), então só ele quebra. É justamente o chunk do spawn, o
  primeiro que o Multiverse carrega.

Este plugin implementa `generateBlockSections`. Com ele o Crucible monta cada seção pelo
construtor `ExtendedBlockStorage(int, boolean, byte[], byte[])`, que não passa pelos métodos
bloqueados pelo EndlessIDs. É o mesmo construtor usado pelo `PlotMe-DefaultGenerator`
(`generateExtBlockSections`) do `plotworld`.

## Instalação

1. Desligue o servidor.
2. Remova `plugins/VoidGenerator.jar` e coloque `dist/AkashicVoidGenerator-1.0.0.jar` em `plugins/`.
3. Ligue o servidor e crie o mundo:

   ```
   /mv create Spawn normal -t flat -g AkashicVoidGenerator
   ```

O `-t flat` é opcional. Num mundo void ele deixa o horizonte do céu em y=0, em vez de y=63.

Pré-requisito: o log de boot precisa mostrar
`[Akashic Bukkit-EndlessIDs Compat] Patched CustomChunkGenerator biome bridge safely`.
Esse coremod cuida do array de biomas, que todo gerador Bukkit usa. Se aparecer `REFUSED patch`,
qualquer gerador Bukkit vai quebrar na parte de biomas.

## Build e teste

```
./build.sh "<crucible-server.jar>" [plugins/VoidGenerator.jar]
```

O script compila para Java 8 e gera `dist/AkashicVoidGenerator-<versão>.jar`. Depois roda
`CustomChunkGeneratorSimulation`, que é uma cópia linha a linha da parte de blocos do
`CustomChunkGenerator.provideChunk` do Crucible, com uma seção cujo `getBlockLSBArray()` lança
exceção igual ao EndlessIDs. São gerados 676 chunks (25x25 ao redor do spawn). Se você passar o
`VoidGenerator.jar` antigo, a simulação reproduz o crash dele no chunk (0,0).
