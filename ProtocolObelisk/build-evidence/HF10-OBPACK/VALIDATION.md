# HF10 OBPACK — evidências de validação

Data: 2026-10-01. Ambiente: container Linux, JDK 21, Python 3, NeoForge instalado das ServerFiles
oficiais. Não houve cliente Minecraft: nenhuma entrada real foi executada.

## Capturas

| Pacote | ServerFiles (SHA-256) | NeoForge | Boots | Resultado |
|---|---|---|---|---|
| `atm10-8.1` | `259e4a98888ee6ded0b439113c19ac3c79f6e90eeab1a2c465a1c005d0d5f3c4` | 21.1.249 | 1 | Reproduz a evidência de produção embutida da 8.1: 10/11 arquivos byte-idênticos; Arcanus semanticamente idêntico (só a ordem de chaves NBT difere). |
| `atm10-8.2` | `8f1ef6e6969924bc18a7194782ea9266f7a0bde41141dd94776b5225316d3278` | 21.1.251 | 2 | 406 arquivos: 397 byte-idênticos e 9 semanticamente idênticos (listas NBT montadas a partir de sets e ids de tags de registries estáticos). |

Pacotes gerados (não versionados porque contêm configs do ATM10):

- `atm10-8.1.obpack`: 2.427.234 bytes, SHA-256 `d5fcc8c5332c610e144a42c8d73c4dbb3d5facf2d8823c6a41a8d3f0288551d8`
- `atm10-8.2.obpack`: 2.435.575 bytes, SHA-256 `a9fb8d2f2da2e2f8b71255778d710e06e81554e56ef4d98b6903d7287c1692b9`

Os relatórios dos pacotes estão em `pack-report-atm10-8.1.md` e `pack-report-atm10-8.2.md`. O
diff está em `diff-atm10-8.1-to-8.2.md`.

## Entrega calculada pelo plugin (pacotes reais)

| | 8.1 | 8.2 |
|---|---|---|
| SERVER configs | 288 | 290 |
| Registries que o Paper não envia (anexados inteiros) | 40 (408.074 bytes) | 41 (416.087 bytes) |
| Registries do Paper estendidos (exceto encantamentos) | 9 (899 entradas, 199.841 bytes) | 9 (874 entradas, 198.080 bytes) |
| `minecraft:enchantment` | substituição: 139; extensão: 97 | substituição: 139; extensão: 97 |
| Registries em quarentena | 0 | 0 |
| Estados BlockState globais | 1.543.003 | 1.548.078 |

## Lista vanilla 1.21.1

`velocity-plugin/src/main/resources/vanilla-registries/minecraft-1.21.1-synchronized.tsv`: 313
entradas em 11 registries, geradas de `data/minecraft/<registry>/*.json` do servidor oficial
1.21.1. O SHA-256 da sequência `registry\tentrada\n` é
`56f0ce758b71b436b6c7c69ac671122cc252efa49c5af9ebc2efaf59ed87fa85`, idêntico ao
`known-pack-entry.sequence-sha256` revisado do perfil ATM10 8.0. Em ambas as capturas, toda
entrada sem dados (placeholder do known pack) é vanilla e toda entrada vanilla está presente.

## Fechamento de referências

`CapturedCompatibilityPackTest.everyReferenceInDeliveredRegistriesResolvesOnTheClient` cobre cada
pacote real nos dois modos de encantamento (substituição e extensão). Ele exige que todo
identificador citado no NBT de um pacote entregue seja recebido pelo cliente em **todo** registry
do servidor capturado que tenha uma entrada com esse id, seja como entrada vanilla do Paper ou como
entrada entregue. A comparação é por registry: `eternal_starlight:dark_swamp`, por exemplo, é ao
mesmo tempo um bioma e uma entrada de `eternal_starlight:biome_data`.

Controle negativo, com a mesma regra e sem as extensões dos registries do Paper:

| Pacote | Sem extensões | Com extensões |
|---|---|---|
| 8.1 | 8 registries entregues citam entradas ausentes | 0 |
| 8.2 | 9 registries entregues citam entradas ausentes | 0 |

Na 8.2, `eternal_starlight:biome_data`, `boarwarf_type`, `ent_variant`, `seeker_variant` e
`shimmer_lacewing_variant` citam biomas do Eternal Starlight. `alloy_furnace_coolant` cita
`eternal_starlight:glacite`, que também é trim material. Em `minecraft:enchantment`,
`moonlight:soft_fluid` e `twilight:restrictions`, os ids coincidem com damage types de mods (a
regra conservadora conta qualquer coincidência).

## Testes

`./gradlew check -PprotocolObeliskRealPacks=<pasta com os dois .obpack>`:

- Velocity: 568 testes, 0 falhas, 0 pulados.
- Paper: 40 testes, 0 falhas, 0 pulados.

## Build

- JAR Gradle `ProtocolObelisk-Velocity-1.9.16-EVOLUTION.jar`:
  `8c15e3adb3163dcf85c8532f7db2fcf9641eeaf76b20f65bd4102595749c58fe`, idêntico em dois builds
  limpos.
- `tools/hf10-obpack/package_hf10.py` gera
  `ProtocolObelisk-Velocity-1.9.16-EVOLUTION-HF10-OBPACK-CANDIDATE.jar`:
  `52f5e74dec68850c50321f6a6bf8f25a05c40e53a16b0dd75650b32128a7e085`, idêntico em duas execuções.
  Em relação ao JAR Gradle, só mudam `velocity-plugin.json`, `META-INF/MANIFEST.MF` e o marcador
  `META-INF/protocolobelisk-hf10-obpack.json`.

## Não executado

Cliente Minecraft real, sessão Velocity/Paper real, codecs reais do NeoForge no cliente.
