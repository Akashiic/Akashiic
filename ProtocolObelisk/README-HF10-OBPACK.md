# ProtocolObelisk — HF10 OBPACK CANDIDATE

Estado: candidata. Validada por testes automatizados contra capturas reais das ServerFiles
oficiais do ATM10 8.1 e 8.2, **não** por uma entrada real de cliente Minecraft. Não instalada
remotamente.

Artefato: `ProtocolObelisk-Velocity-1.9.16-EVOLUTION-HF10-OBPACK-CANDIDATE.jar`
SHA-256: `52f5e74dec68850c50321f6a6bf8f25a05c40e53a16b0dd75650b32128a7e085`

O gerenciador de plugins do Velocity deve mostrar `1.9.16-EVOLUTION-HF10-OBPACK-CANDIDATE`.
As mensagens históricas do núcleo continuam dizendo `1.9.16-EVOLUTION`. Para identificar a
build, use o gerenciador.

## A ideia: pacotes de compatibilidade em vez de código por versão

Até a HF9, cada versão do ATM10 exigia evidência embutida no código: listas de configs,
registries, mapas de BlockState e hashes de contrato. Quando a 8.2 saiu, nada disso existia para
ela. O resultado foi um cliente servido pela metade.

A HF10 troca esse modelo por **pacotes de compatibilidade** (`.obpack`). Cada pacote é um arquivo
por versão de modpack, capturado automaticamente das ServerFiles oficiais pela ferramenta
`tools/obelisk-capture`. Atualizar para o ATM10 8.3 passa a ser assim: capturar o pacote 8.3,
colocar o arquivo na pasta `packs` e reiniciar o proxy. Não é preciso mudar o código-fonte.

O pacote contém exatamente o que um servidor NeoForge real entregaria ao cliente durante a
CONFIGURATION:

| Conteúdo | ATM10 8.2 | O que a HF9 entregava para a 8.2 |
|---|---|---|
| SERVER configs (`neoforge:config_file`) | 290, com o conteúdo real | 23 |
| Registries que o Paper não envia | 41 inteiros | 1 (Arcanus) |
| Entradas de mods nos registries que o Paper envia | 874 em 9 registries (biomas, damage types, dimension types, trims, pinturas, banners, discos, wolf variant) | 0 |
| `minecraft:enchantment` | substituído pelo capturado (139), ou estendido com 97 | merge de 4 entradas (Giselle) |
| Tags desses registries | as capturadas | só as do Paper |
| Mapa BlockState vanilla→cliente | o da 8.2 (1.548.078 estados globais) | nenhum (passthrough) |
| Liberação de receitas/JEI | sim, quando a entrega completa é comprovada | retida |

### Seleção: regras do próprio NeoForge, não perfis nem ACL

O pacote é escolhido comparando o `neoforge:register` do cliente com os canais que o servidor
capturado registrou. A regra é a mesma do `NetworkComponentNegotiator` do NeoForge 21.1: canal
obrigatório ausente em qualquer lado falha; num canal comum, se um lado declara direção (flow), o
outro precisa declarar a mesma; a versão precisa ser igual. Isso é verificado em CONFIGURATION e
em PLAY. O pacote é escolhido se, e só se, o servidor real daquela versão aceitaria o cliente.
Assim um cliente 8.2 nunca recebe o pacote 8.1: entre as duas versões, 294 canais mudaram de
versão, direção ou obrigatoriedade, 52 surgiram e 4 sumiram.

- O pacote **nunca** decide admissão nem roteamento. Sem pacote compatível, o cliente segue o
  caminho genérico de sempre, e o motivo vai para o log e para um relatório.
- Os perfis embutidos e revisados (7.3, 8.0 e 8.1) continuam com precedência. O pacote serve
  todos os outros clientes.
- Se mais de um pacote for compatível, vence o que compartilha mais canais com o cliente. Em
  caso de empate há ambiguidade: nenhum é usado e o log avisa.

### Registries do Paper: só acrescentar, nunca renumerar

O cliente NeoForge do 1.21.1 **anexa** pacotes repetidos do mesmo registry. Por isso a HF10 não
substitui os registries que o Paper envia, como biomas e damage types. Ela anexa, depois das
entradas do Paper, apenas as entradas que o vanilla 1.21.1 não tem. Os ids numéricos que o
mundo do lobby usa (biomas nos chunks, dimension type no login, damage types, pinturas) continuam
exatamente os do Paper. Overrides de entradas vanilla feitos pelo modpack (5 biomas do End, 9
wolf variants e `the_end`) ficam com a definição do Paper. Com
`compatibility-pack-replace-enchantment-registry=false`, o mesmo vale para 3 encantamentos; com
`true`, o registry de encantamentos inteiro é o capturado. Esse é o mesmo
método que funcionou em produção nos perfis 7.3 e 8.0.

Isso é obrigatório e não opcional. Registries da 8.2 como `eternal_starlight:biome_data`,
`boarwarf_type`, `ent_variant`, `seeker_variant` e `shimmer_lacewing_variant` citam biomas do
Eternal Starlight. Se o pacote os entregasse sem os biomas, o cliente falharia ao carregar
**todos** os registries no fim da CONFIGURATION (`Unbound values in registry
minecraft:worldgen/biome`). Um teste automatizado percorre cada identificador citado em cada
pacote entregue, nos dois modos de encantamento, e prova que o cliente recebe tudo o que é
citado.

A lista do que é vanilla é a lista exata das 313 entradas do 1.21.1. O SHA-256 dela
(`56f0ce75…`) é o mesmo da evidência `known-pack-entry` revisada no perfil 8.0, o que prova que as
duas vêm dos mesmos dados oficiais.

Se um registry de um pacote futuro vier inválido, ele fica em quarentena. Nesse caso, todo pacote
entregue que ainda o cite também é retido, até nada mais mudar, para não reabrir a falha acima.
Se não for possível enumerar o registry inválido, nenhum registry do pacote é entregue. Configs e
BlockStates continuam valendo.

## Causa raiz dos problemas da 8.2 (diagnóstico)

1. **Crashes ao entrar.** O caminho do servidor vanilla no NeoForge carrega os SERVER configs
   padrão. O caminho NeoForge, que o ProtocolObelisk imita, carrega somente os configs que o
   servidor envia. Qualquer `ConfigValue.get()` de um config não enviado lança
   `Cannot get config value before config is loaded`. Esta é a classe dos crashes das HF4 a HF9.
   A HF9 enviava 23 dos 290 configs da 8.2.
2. **Blocos trocados.** Nenhum mapa BlockState correspondia ao contrato 8.2, então os ids do Paper
   iam crus para um cliente cujo registro de blocos é outro. A projeção da 8.2 é idêntica à da
   8.1: `note_block` com +150 estados, `skeleton_skull` com `waterlogged` e `water_cauldron` com
   `boiling`. Só o total global mudou.
3. **Registries ausentes.** Só 1 dos 41 registries de mods era entregue.
4. **Receitas retidas.** Sem evidência estrutural, o ciclo de receitas/JEI ficava `WITHHELD`.

## Instalação

1. Pare o Velocity. Guarde o JAR HF9 e uma cópia de `plugins/protocolobelisk/bridge.properties`
   fora da pasta `plugins`.
2. Substitua só o JAR principal por
   `ProtocolObelisk-Velocity-1.9.16-EVOLUTION-HF10-OBPACK-CANDIDATE.jar`. Não carregue HF9 e HF10
   juntas.
3. Crie `plugins/protocolobelisk/packs/` e coloque nela `atm10-8.2.obpack`. Opcionalmente,
   coloque também `atm10-8.1.obpack`; clientes 8.1 continuam usando o perfil embutido revisado.
   Não renomeie o conteúdo nem extraia o arquivo, porque ele é verificado por SHA-256.
4. Não altere o companion Paper, os mods do cliente nem os auxiliares. Não é preciso criar
   datapack ou TOML em lugar nenhum.
5. O `bridge.properties` existente serve como está: as chaves novas assumem os padrões abaixo.
   Inicie o Velocity sem hot reload e reinicie o cliente.

### Chaves novas (`bridge.properties`)

| Chave | Padrão | Efeito |
|---|---|---|
| `enable-compatibility-packs` | `true` | Liga o carregamento e a seleção de pacotes. |
| `compatibility-pack-server-configs` | `captured` | `captured` envia o conteúdo real dos configs. `empty` envia cada nome com TOML vazio e o cliente usa os próprios padrões. |
| `compatibility-pack-replace-enchantment-registry` | `true` | `true` substitui o registry de encantamentos do Paper pelo capturado, com tags. `false` só anexa os encantamentos de mods. |
| `compatibility-pack-release-recipes` | `true` | Libera receitas/JEI quando a entrega completa é comprovada. |
| `forced-compatibility-pack` | vazio | Id de pacote usado **só** quando nenhum negocia. Uso de diagnóstico; deixe vazio. |
| `compatibility-pack-mismatch-reports` | `true` | Grava em `plugins/protocolobelisk/compatibility-reports/` o motivo de um cliente não casar com nenhum pacote (máx. 128 arquivos). |

## O que verificar nos logs

Na inicialização do proxy:

```
Compatibility pack armed: id=atm10-8.2, name='All the Mods 10 8.2', file=atm10-8.2.obpack,
  sha256=a9fb8d2f…, NeoForge=21.1.251, mods=502, serverChannels=…, serverConfigs=290,
  tailRegistries=41, paperRegistryExtensions=9 (874 entries), enchantmentRegistry=139 entries,
  blockStates=1548078 states, quarantinedRegistries=[], selection=NEOFORGE_NEGOTIATION, …
```

`quarantinedRegistries` deve vir vazio. Na entrada de um cliente 8.2:

- `compatibilityPack=atm10-8.2 selected for <jogador>: status=NEGOTIATED, … serverConfigs=290 (captured), tailRegistries=41, enchantmentRegistry=replace, blockStates=translate`
- `Completed Paper enchantment registry transform … evidence=pack:atm10-8.2`
- `Starting compatibility-pack registry injection … extensions=9, tailRegistries=41, … enchantmentReplaced=true`
- `Completed compatibility-pack registry injection … flushes=2`

Se aparecer `compatibilityPack=none … status=NO_COMPATIBLE_PACK`, o log mostra o pacote mais
próximo e as primeiras diferenças. O relatório completo fica em `compatibility-reports/`. Isso
significa que o cliente não é a versão capturada; normalmente basta capturar a versão nova.

## Atualizar para uma nova versão do modpack

Veja `tools/obelisk-capture/README.md`. Em resumo, numa máquina Linux com Java 21, Python 3,
cerca de 12 GB de RAM livres e internet para o instalador do NeoForge:

```sh
tools/obelisk-capture/run-capture.sh --server-files ServerFiles-8.3.zip --pack-id atm10-8.3 \
  --display-name "All the Mods 10 8.3" --work /tmp/obelisk-8.3 --out atm10-8.3.obpack --accept-eula
python3 tools/obelisk-capture/obpack.py diff atm10-8.2.obpack atm10-8.3.obpack --out diff-8.2-8.3.md
```

O script faz dois boots limpos e descartáveis, exige que as duas capturas concordem e gera o
pacote. O `diff` mostra em português o que mudou: mods, canais, configs, registries, tags e o
mapa BlockState. Depois, coloque o `.obpack` na pasta `packs` e reinicie o Velocity.

## Evidências de validação

- **A captura reproduz a evidência de produção da 8.1.** Comparada aos recursos embutidos que
  funcionam em produção, 10 de 11 arquivos são byte-idênticos. O 11º (Arcanus) é semanticamente
  idêntico e difere só na ordem de chaves NBT.
- **A captura da 8.2 é reproduzível.** Dois boots independentes concordam em 406 arquivos: 397
  byte-idênticos e 9 semanticamente idênticos. Os 9 vêm de listas NBT montadas a partir de sets e
  de ids de tags de registries estáticos, que o próprio servidor ordena de forma não
  determinística.
- **Diff 8.1→8.2.** Há 115 mods alterados e 3 novos; +2 SERVER configs
  (`ae2importexportcard-server.toml`, `copycats-server.toml`) e 5 com conteúdo alterado; 294
  canais alterados, 52 novos e 4 removidos. O mapa BlockState tem a mesma projeção, com outro total global.
  Detalhes em `build-evidence/HF10-OBPACK/diff-atm10-8.1-to-8.2.md`.
- **Fechamento de referências.** Teste automatizado sobre os pacotes 8.1 e 8.2 reais nos dois
  modos de encantamento: nenhum identificador citado fica sem entrada no cliente.
- **Testes.** `./gradlew check` com `-PprotocolObeliskRealPacks=<pasta com os .obpack>`: Velocity
  568/568 e Paper 40/40, sem testes pulados. Isso inclui seleção (aceita o próprio servidor e
  rejeita clientes reais 7.3 e 8.0), integridade (manifesto SHA-256, limites, nomes de entrada),
  quarentena, configs e tags.
- **Build reproduzível.** Dois builds limpos geram o mesmo JAR Gradle
  (`8c15e3ad…c58fe`), e o carimbo HF10 gera o mesmo JAR final. O carimbo só altera
  `velocity-plugin.json`, a `Implementation-Version` do manifesto e um marcador; todas as classes
  e recursos são copiados byte a byte.

Pacotes usados nos testes (não versionados no repositório, porque contêm dados do ATM10):

| Pacote | Origem | SHA-256 |
|---|---|---|
| `atm10-8.1.obpack` | ServerFiles-8.1.zip (`259e4a98…`) | `d5fcc8c5332c610e144a42c8d73c4dbb3d5facf2d8823c6a41a8d3f0288551d8` |
| `atm10-8.2.obpack` | ServerFiles-8.2.zip (`8f1ef6e6…`) | `a9fb8d2f2da2e2f8b71255778d710e06e81554e56ef4d98b6903d7287c1692b9` |

## Limitações conhecidas e o que não foi testado

- **Nenhuma entrada real foi feita.** O ambiente de build não tem cliente Minecraft. O
  comportamento do cliente foi derivado do código do NeoForge 21.1.251 e do Minecraft 1.21.1,
  além do que já funciona em produção nas 7.3, 8.0 e 8.1. Não foi observado numa sessão.
- **Cores de bioma em seções com mais de 8 biomas distintos.** Com os biomas de mods anexados, o
  cliente espera 9 bits na paleta global de biomas, e o Paper escreve 6. O cliente lê a seção sem
  dessincronizar, mas mostra o primeiro bioma do registry naquela seção. É só cosmético e raro
  num lobby; os perfis 7.3 e 8.0 já tinham o mesmo comportamento.
- **Encantamentos em itens do lobby.** Com `compatibility-pack-replace-enchantment-registry=true`,
  como já acontecia na 8.1, os ids numéricos de encantamento passam a ser os do servidor
  capturado. Um item encantado enviado pelo Paper pode mostrar outro encantamento. Use `false`
  para manter os ids do Paper; nesse caso as tags de encantamento ficam as do Paper.
- **Datapacks no lobby.** Se o Paper tiver um datapack que crie, num desses registries, uma
  entrada com o mesmo id de uma entrada do modpack, o cliente recusará a duplicata. Datapacks
  comuns de lobby não fazem isso.
- **Data maps e registries estáticos.** O NeoForge também sincroniza data maps. Eles não são
  capturados e ficam vazios no cliente, como já acontecia; isso não afeta o lobby.

## Rollback

Pare o Velocity, remova o JAR HF10, reinstale o JAR HF9 preservado e inicie. A pasta `packs`
pode ficar: a HF9 a ignora. Não é preciso apagar mundo, dados ou configurações.

## Reconstrução

Com JDK 21 e Python 3, a partir deste diretório:

```sh
./gradlew check :velocity-plugin:jar -PprotocolObeliskRealPacks=/abs/pasta/com/obpacks
python3 tools/hf10-obpack/package_hf10.py \
  velocity-plugin/build/libs/ProtocolObelisk-Velocity-1.9.16-EVOLUTION.jar /tmp/hf10-out
```

Sem `-PprotocolObeliskRealPacks`, os testes que usam pacotes reais são pulados e o resto da suíte
roda normalmente.
