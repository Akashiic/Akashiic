# obelisk-capture — gerador de pacotes de compatibilidade (`.obpack`)

Esta ferramenta captura, das ServerFiles oficiais de um modpack, tudo o que um servidor NeoForge
real entrega ao cliente durante a CONFIGURATION. O resultado é um pacote que o ProtocolObelisk
(HF10+) carrega em tempo de execução. É assim que uma versão nova do modpack passa a ser
suportada sem mudar o código do plugin.

## Requisitos

- Linux com `bash`, `unzip`, `curl`, Java 21 (`java`, `javac`, `jar`) e Python 3.
- Cerca de 12 GB de RAM livres (padrão `--xmx 10G`) e uns 5 GB de disco por versão.
- Internet durante a instalação do NeoForge (instalador e bibliotecas). Os boots em si não
  precisam de rede.
- O zip das ServerFiles oficiais (ex.: `ServerFiles-8.2.zip` do ATM10).

## Uso

```sh
tools/obelisk-capture/run-capture.sh \
  --server-files /caminho/ServerFiles-8.2.zip \
  --pack-id atm10-8.2 \
  --display-name "All the Mods 10 8.2" \
  --work /tmp/obelisk-8.2 \
  --out /caminho/atm10-8.2.obpack \
  --accept-eula
```

| Opção | Padrão | Significado |
|---|---|---|
| `--server-files` | obrigatório | Zip oficial das ServerFiles. |
| `--pack-id` | obrigatório | Id do pacote: `[a-z0-9][a-z0-9._-]{0,63}`. Aparece nos logs do proxy. |
| `--work` | obrigatório | Diretório de trabalho descartável. A instalação fica em `base/` e é reaproveitada. |
| `--out` | obrigatório | Arquivo `.obpack` gerado. O script se recusa a sobrescrever. |
| `--accept-eula` | obrigatório | Declara que você aceita a EULA do Minecraft para este servidor descartável. |
| `--display-name` | o pack id | Nome legível. |
| `--source-url` | vazio | URL de origem das ServerFiles, registrada no pacote. |
| `--boots` | `2` | Quantos boots independentes; todos precisam concordar. |
| `--xmx` | `10G` | Heap de cada boot. |
| `--timeout-minutes` | `45` | Tempo máximo por boot. |

### O que o script faz

1. Extrai as ServerFiles e instala o NeoForge indicado nelas (`--installServer`).
2. Compila o mod de captura (`build-offline.sh`) contra exatamente esse NeoForge.
3. Em cada boot, copia a instalação limpa, adiciona o mod de captura e sobe um servidor
   descartável: mundo plano, `online-mode=false`, porta aleatória, sem RCON nem query. O mod
   recusa híbridos (Youer/Mohist), exige o protocolo 767 (1.21.1), exporta e desliga o servidor.
4. Compara as capturas (`obpack.py compare`). Os arquivos precisam ser byte-idênticos, com uma
   exceção: onde o próprio servidor ordena de forma não determinística (listas NBT montadas a
   partir de sets, ids de tags de registries estáticos), a igualdade exigida é semântica.
5. Empacota (`obpack.py pack`) e imprime o relatório (`obpack.py inspect`).

Nada disso toca o servidor de produção, o lobby, clientes ou mundos.

## Conteúdo do pacote

Zip determinístico (datas fixas, ordem fixa) com `manifest.sha256` listando o SHA-256 de cada
entrada. O plugin recusa o pacote inteiro se uma entrada faltar, sobrar ou tiver hash diferente.

| Entrada | Conteúdo |
|---|---|
| `pack.properties` | Id, nome, versões de Minecraft/NeoForge, origem (arquivo, tamanho e SHA-256 das ServerFiles) e hashes de cada parte. |
| `mods.tsv` | Mods carregados no servidor, com versões. |
| `network/server-query.bin`, `network/channels.tsv` | Os canais que o servidor registra, no formato exato do `neoforge:register`. É o que o proxy usa para decidir, pelas regras de negociação do NeoForge, se o pacote serve um cliente. |
| `server-configs/NNN.bin`, `server-configs.properties` | Todos os SERVER configs exatamente como `neoforge:config_file` os envia. |
| `registries/wire-known-pack/*.bin` + `.properties` | Cada registry sincronizado, como o servidor o envia a um cliente que negociou só `minecraft:core`, que é o caso do lobby Paper. |
| `tags/full-update-tags.bin` | As tags enviadas na CONFIGURATION. |
| `block-states/block-state-map.bin` + `.properties`, `extra-vanilla-properties.tsv` | Mapa de cada BlockState vanilla para o id que o cliente desse modpack usa, e o que os mods acrescentam a blocos vanilla. |
| `capture.properties` | Quantos boots concordaram. |
| `report.md` | Resumo legível (o mesmo de `obpack.py inspect`). |

Os pacotes contêm os configs do modpack. Distribua-os como arquivos de operação e **não** os
publique em repositórios públicos; o `.gitignore` deste repositório já ignora `*.obpack`.

## Outros comandos

```sh
python3 tools/obelisk-capture/obpack.py inspect atm10-8.2.obpack          # resumo em português
python3 tools/obelisk-capture/obpack.py diff atm10-8.1.obpack atm10-8.2.obpack --out diff.md
python3 tools/obelisk-capture/obpack.py compare /tmp/w/capture-1 /tmp/w/capture-2
```

O `diff` lista mods, canais (incluindo mudanças de versão e de obrigatoriedade), SERVER configs
novos, removidos e alterados, registries e suas entradas, tags e o mapa BlockState. No mapa, ele
decodifica os dois lados e diz se a projeção vanilla→cliente mudou ou se mudou só o total de
estados globais.

## Instalação do pacote

Coloque o `.obpack` em `plugins/protocolobelisk/packs/` no Velocity e reinicie o proxy. O log
`Compatibility pack armed: id=…` confirma que o pacote foi carregado; `quarantinedRegistries=[]`
confirma que todos os registries foram aceitos. Veja `README-HF10-OBPACK.md` na raiz para os
detalhes de seleção, entrega e diagnóstico.
