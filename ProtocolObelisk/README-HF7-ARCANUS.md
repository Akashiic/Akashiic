# ProtocolObelisk — HF7 ARCANUS CANDIDATE

Estado: candidata de correção específica; não homologada em Minecraft. Não instalada remotamente.

## Correção

A HF6 inicializou Create; o incidente seguinte passou a `Missing element ResourceKey[forbidden_arcanus:item_modifier / forbidden_arcanus:magnetized]`. A HF7 corrige a exclusão do pacote já existente de Forbidden Arcanus 2.6.1 na seleção do registry tail para o contrato observado do cliente ATM10 8.2.

São seis definições existentes, sem alterar seus bytes: aquatic, demolishing, eternal, fiery, magnetized e soulbound. Não é adicionado um TOML. Configurações permanecem 21 no cenário de operador dos logs.

## Instalação

1. Pare o Velocity. Preserve o JAR HF6 e uma cópia do bridge.properties fora da pasta plugins.
2. Substitua somente o JAR principal do ProtocolObelisk por `ProtocolObelisk-Velocity-1.9.16-EVOLUTION-ATM10-8.2-HF7-ARCANUS-CANDIDATE.jar`. Não carregue HF6 e HF7 juntas.
3. Mantenha Paper, mods do cliente e auxiliares inalterados. Não é necessário remover Forbidden Arcanus, criar um datapack no lobby ou copiar configurações de outro modpack.
4. Inicie o Velocity sem hot reload. Feche e abra o cliente e teste entrada/permanência.

O gerenciador do Velocity deve mostrar `1.9.16-EVOLUTION-ATM10-8.2-HF7-ARCANUS-CANDIDATE`. A mensagem histórica do núcleo pode continuar mostrando 1.9.16-EVOLUTION; use a identificação do gerenciador.

## Diagnósticos esperados para o mesmo cliente testado

- `baselineTransientConfigs=21` (mantida a configuração do operador).
- Na sessão de Akashiic, `registryShims=[forbidden-arcanus-2.6.1]`.
- `registryShimPackets=1`, `registryShimEntries=6`, `registryShimBytes=2747`.
- O receipt de `forbidden_arcanus:item_modifier` deve ter SHA-256 `bd73a8eb99de591e7bbcf2a16ffef24e69d17f30a6bdcbd691b4dc02c2d26db3`.
- O receipt separado do merge Giselle continua possível; ele não entra nessa contagem de um pacote de tail.

`registryShims=[]` nessa sessão indica que a regra não foi aplicada; preserve o log, em vez de liberar shims arbitrários.

## Escopo e opt-out

A regra exige protocolo 767, namespace forbidden_arcanus anunciado e o contrato de canais observado:
`65ddebdbecf3a09d57c2fcc3ce39099df539fdf9260181c1e31b90afdc3f9577`.
Ela também exige que o shim revisado esteja na lista efetiva permitida pelo operador. A configuração dos seus logs já o inclui. A build não reabilita um shim expressamente removido.

O hash de canais correlaciona a sessão revisada, mas não é atestado criptográfico dos JARs ou datapacks. Mudanças no conjunto/versão dos canais do cliente podem impedir essa regra de corresponder. Clientes desconhecidos mantêm a política adaptativa de entrada; não recebem automaticamente esse registro. A regra não é uma ACL e não libera o catálogo inteiro da 8.1.

## Validação

Java 21 estrito; 62 verificações de registry contra o JAR HF7; 50 contra a HF6 reproduzindo a omissão; as 105 verificações de configuração da HF6 passaram contra ambos os JARs. Decoder Python independente conferiu todos os campos das seis definições. ABI de RegistryShimCatalog preservada e delta do JAR auditado.

Não foram executados Minecraft, os codecs reais de NeoForge/Forbidden Arcanus, sessão real Velocity/Paper, ou suíte completa JUnit/Netty. A candidata trata a causa observada, não comprova o funcionamento integral da 8.2.

## Pendências e teste

Primeiro verificar entrada, movimentação e permanência. Depois tratar inventário/JEI, menus e transições como validações distintas. BlockState 8.2 ainda não certificado e recipeLifecycle WITHHELD permanecem. O auxiliar HotbarGuard não foi modificado.

Em novo crash, preserve crash report, latest.log do cliente e log do Velocity da mesma tentativa. Um erro diferente não deve ser declarado resolvido sem evidência própria.

Rollback: pare o Velocity, remova HF7, recoloque HF6 preservada e inicie. A HF6 mantém a lacuna de registry conhecida; rollback não é solução para esse crash.

SHA-256 do JAR HF7: `f10a1429d7c549e6ad70ea64848bc93dc05ad4f81026ac22a128327aff892f26`.

Os arquivos bridge.properties em evidence são fixtures de teste: não os instale no servidor.
