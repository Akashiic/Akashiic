# ProtocolObelisk HF9 ELEVATOR — candidata de teste

Base: HF8 SOPHISTICATEDBACKPACKS, SHA-256
`67099ca4fddf70013e9b171fcb3760a3f9fced7f669dc7006557d93fc7b85827`.

Artefato: `ProtocolObelisk-Velocity-1.9.16-EVOLUTION-ATM10-8.2-HF9-ELEVATOR-CANDIDATE.jar`.
SHA-256: `c2744c0a886cc37715648b224113eeb8e2fa1f617d110af5fd8ad50dd374c191`.

## Escopo

Corrige a omissão de `elevatorid-server.toml` no conjunto básico revisado de
CONFIGURATION. A configuração será inicializada pelo transporte existente com
TOML vazio; o próprio cliente preenche seus padrões. Nenhum valor de alcance,
teleporte, gravidade ou força de salto é forçado.

**Esta build NÃO corrige os blocos trocados da 8.2.** Ela não contém um mapa
BlockState novo, não reutiliza um mapa 8.1 para outro contrato e não libera
artificialmente o ciclo de receitas/JEI. Leia `DIAGNOSTICO-HF9.md`.

## Instalação

1. Pare o Velocity. Guarde o JAR HF8 e uma cópia de `bridge.properties` fora de `plugins`.
2. Instale somente o JAR principal HF9 no Velocity, sem manter HF8 e HF9 juntas.
3. Não altere o companion Paper, os auxiliares nem os mods do cliente.
4. Preserve `bridge.properties`. Inicie o proxy sem hot reload e reinicie o cliente.

Com `enable-built-in-atm-server-configs=true`, a inclusão é automática em memória,
mesmo usando o arquivo v4 anterior. O arquivo existente não é substituído.
Se você desativou expressamente esse grupo de built-ins, a opção continua
respeitada. Para usar a correção com esse opt-out, acrescente exatamente
`elevatorid-server.toml` à lista existente `transient-server-configs`, mantendo
os outros nomes; faça backup e reinicie o Velocity. Não crie um TOML manualmente
no Paper, no proxy ou no cliente.

## Verificação do teste

O gerenciador de plugins deve identificar `1.9.16-EVOLUTION-ATM10-8.2-HF9-ELEVATOR-CANDIDATE`.
Mantidas as opções da rodada enviada, o baseline e o conjunto da sessão passam
de 22 para 23 configurações, incluindo `elevatorid-server.toml` uma única vez.
O total pode variar se o operador alterar suas opções: a presença e a entrega
do nome correto são os critérios relevantes.

Teste entrar, autenticar, pular e agachar. Não interprete o desaparecimento desse
crash como validação das colisões, blocos, inventário/JEI ou transições. A sessão
anterior não tinha tradução visual válida; não quebre/construa blocos para tentar
corrigir a aparência no cliente.

Rollback: pare o proxy, retire HF9, reinstale apenas o JAR HF8 preservado e reinicie.
Não é preciso apagar dados, mundo ou configurações para reverter esse overlay.

## Reconstrução

Extraia o arquivo `*-source.zip`. Use JDK 21 e Python 3, com o HF8 original:

```sh
python3 tools/hf9-elevator/build_hf9.py /caminho/para/HF8-original.jar /caminho/novo/build-hf9
```

O diretório de saída não pode existir. O script valida SHA-256 da base, compila
somente a classe alterada com `--release 21 -Xlint:all -Werror`, monta o overlay,
executa os harnesses reais de configuração/planejamento/codificação e Arcanus,
compara a ABI e verifica o delta do JAR. Os scripts HF4–HF8 e seus relatórios
presentes nos fontes são históricos; use o comando HF9 acima para esta release.
Isto não é uma execução completa do build Gradle nem uma integração de Minecraft.


# Diagnóstico HF9 — salto e BlockStates são problemas distintos

## Evidências desta rodada

Arquivos enviados: `crash-2026-09-28_00.55.06-client.txt`,
`latest(20260928-035621).log`, `latest (25)(3).log` (Velocity) e
`latest (26)(2).log` (Paper). Data: 28/09/2026.

O Velocity carregou HF8 SOPHISTICATEDBACKPACKS e registrou autenticação bem-sucedida.
A lista efetiva tem 22 configurações; não contém `elevatorid-server.toml`.
O prefixo concluiu escrita com sucesso. A injeção Arcanus concluiu um pacote,
seis entradas e 2.747 bytes. O cliente mostra os pesos do Sophisticated Backpacks
preenchidos a partir da configuração, incluindo leatherWeight=625.
O jogador desta sessão é o ID 178. Não confundir com IDs 176 e 177 das rodadas
anteriores ainda presentes no log cumulativo do Paper.

O crash atual é `IllegalStateException: Cannot get config value before config is loaded`
em `ModConfigSpec$IntValue.getAsInt -> ElevatorHandler.getOriginElevator:79 ->
tryTeleport:42 -> handleInput:34 -> ClientTickEvent.Post`.
O arquivo identifica ElevatorMod 1.21.1-1.11.4, Minecraft 1.21.1, NeoForge
21.1.251 e ATM10 8.2. Não é o stack fatal do Sophisticated Backpacks ou Arcanus.

O código oficial do commit `129614f8e54de6ddca3c5db757fa8ebc33f40a9d` declara
`mod_version = 1.21.1-1.11.4`. `handleInput` trata a transição para pular e para
agachar. `getOriginElevator` lê `Config.GENERAL.activationRange.getAsInt()` antes
da consulta ao primeiro bloco. `ElevatorModNeoForge` registra `Config.SPEC` como
SERVER sem filename alternativo: `elevatorid-server.toml`. O padrão de
activationRange é 6, mas esse número não é enviado pelo patch: a especificação
do cliente continua sendo a origem do valor. Esse caminho pode falhar mesmo
sem existir um elevador sob o jogador.

Os snapshots Paper depois da autenticação mantêm gravidade 0,08 e força de salto
aproximadamente 0,42; velocidades normais são restauradas e os efeitos de restrição
pré-login deixam de aparecer. Não foi identificada nesses snapshots uma alteração
anormal de gravidade que justifique corrigir o crash mexendo no movimento.
O reset de conexão do proxy sucede a interrupção do cliente; não substitui o stack
fatal na identificação da causa. Os arquivos têm horários de eventos e geração do
relatório diferentes. Não foram usados uptime da JVM, ticks acumulados nem o tempo
até o encerramento TCP para afirmar duração exata de permanência saudável.

## Alteração implementada

Somente `BridgeConfig.class` mudou funcionalmente. A lista de built-ins e o modelo
para configuração nova incluem `elevatorid-server.toml`, depois das quatro configs
NeoForge/Create/Hypertube/Sophisticated Backpacks. Nenhuma rotina de movimento,
permissão, autenticação, roteamento, seleção de perfil, registries ou receitas foi
modificada. Configs antigas são preservadas em disco; opt-out dos built-ins é
respeitado. Os 22 payloads da lista observada mantêm os mesmos bytes e ordem relativa.
O acréscimo é um payload ConfigFile de 24 bytes: nome UTF-8 de 22 bytes, VarInt do
comprimento do nome e VarInt de comprimento zero do conteúdo. Esse tamanho não inclui
framing externo de pacote, canal ou compressão.

## BlockStates — NÃO corrigidos nesta release

O log atual é explícito:

```text
compatibilityCapability=BLOCKSTATE_MAP_UNAVAILABLE
visualBlockStateCompatibility=UNVERIFIED_PASSTHROUGH
evidenceStatus=NO_EXACT_EMBEDDED_EVIDENCE
```

Nenhum tradutor revisado foi anexado ao contrato observado
`65ddebdbecf3a09d57c2fcc3ce39099df539fdf9260181c1e31b90afdc3f9577`.
`blockStateRuntimeReadiness=NOT_REQUIRED` significa que a política permite a
admissão sem esperar pelo mapa; não prova que o mapa seja desnecessário à exibição.
O pacote herdado tem a captura revisada 8.1/NeoForge21.1.249 e outra superfície 8.0.
Não existe neste overlay qualquer nova captura 8.2. A busca na Biblioteca trouxe
instruções e evidências anteriores 8.1, não uma tabela 8.2 utilizável.

A falha demonstrada é a ausência de tradução dos IDs de estado do Paper para os
IDs interpretados pelo cliente modded. Os logs não contêm a tabela completa de
correspondência necessária para reconstruir esse mapa, nem demonstram corrupção
do arquivo de mundo do Paper. Renomear o mapa 8.1 ou alterar seu contrato declarado
não validaria os IDs 8.2. A contagem global de estados, sozinha, também não é uma
prova suficiente de identidade de mapa.

O caminho técnico para resolver isso é capturar e validar a projeção de todos os
estados canônicos vanilla para os IDs do runtime 8.2 correspondente, incluindo
propriedades adicionadas por mods e largura de paleta. O exportador histórico
`tools/atm10-8.1-runtime-exporter` demonstra o processo, mas possui pins de ZIP e
NeoForge 8.1/21.1.249: ele NÃO foi convertido nem validado para 8.2 nesta rodada.
Um fluxo de captura isolado, apenas de engenharia, evita exigir um mod adicional
nos clientes de produção e evita depender de um Youer de produção para a primeira
entrada. São necessárias capturas reais concordantes, não valores inferidos dos
nomes dos mods. A seleção deve permanecer restrita à evidência verificada.

O ciclo de receitas anterior permanece `WITHHELD`, motivo
`UNREVIEWED_STRUCTURAL_PROFILE_RECIPE_SAFETY`. A HF9 não habilita inventário/JEI
artificialmente para encobrir a falta de registries.

## Validação realmente executada

- Compilação da classe alterada: JDK21.0.11, `--release 21 -Xlint:all -Werror`.
- Harness Elevator/configuração contra HF8: 81 verificações aprovadas, incluindo
  reprodução da omissão do filename no fallback sem perfil.
- O mesmo harness contra HF9: 84 verificações aprovadas, com target presente,
  codificação, limites, persistência, deduplicação e opt-out.
- Harness Arcanus herdado: 62 verificações aprovadas contra HF8 e 62 contra HF9.
- ABI pública e privada de BridgeConfig idêntica.
- 1.019 entradas preexistentes do JAR byte a byte preservadas; três entradas
  alteradas (BridgeConfig, manifest e metadados Velocity); um marcador HF9 adicionado.
- 295 recursos da captura/configurações 8.1 preservados; catálogo exato segue 288
  configs, 651.792bytes codificados e hash agregado original.
- Cada classe e cada recurso BlockState preexistente permanece inalterado.
- Reconstrução em diretório limpo a partir do ZIP de fontes: consultar
  `evidence/reproducibility.json`, gerado após a execução e comparação.

Não foram executados Minecraft, salto/agachamento real, códigos runtime do
Elevator/NeoForge, sessão Velocity/Paper real nem a suíte completa JUnit/Netty.
A tentativa de obter o binário público do Elevator para inspeção local foi impedida
por falha de DNS no container; o trecho de versão exata foi conferido pelo conector
GitHub. Não se declara equivalência binária entre esse source e o JAR instalado.

SHA-256 HF9: `c2744c0a886cc37715648b224113eeb8e2fa1f617d110af5fd8ad50dd374c191`.
Status: candidata de correção da omissão de configuração; não homologada como lobby 8.2.
