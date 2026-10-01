# Diagnóstico e auditoria — HF7 ARCANUS

## Incidente confirmado

Anexos desta rodada: crash-2026-09-28_00.23.18-client.txt, latest(20260928-032411).log e latest (24)(2).log. Hashes em evidence/incident-file-sha256.txt. Os logs brutos não são redistribuídos. Não foi anexado um log novo do Paper nesta rodada; não foi usado um log antigo para certificar o estado do lobby nesta tentativa.

O proxy identifica explicitamente HF6 no gerenciador, registra 21 nomes de configuração e confirma escrita completa do prefixo. O cliente preenche kinetics.contraptions.syncPlayerPickupHitboxWithContraptionHitbox com false, além dos padrões do NeoForge e Hypertube. Assim, a omissão do Create corrigida pela HF6 teve efeito nesta execução; isso não homologa todos os caminhos do Create.

A exceção fatal passou de ConfigValue.get para HolderGetter.getOrThrow: falta o elemento forbidden_arcanus:magnetized no registry forbidden_arcanus:item_modifier. O mixin responsável é forbiddenArcanus_aiStep. A entidade é o próprio jogador Akashiic, ID 176; o crash lista Forbidden Arcanus 2.6.1. Não há fundamento para culpar um NPC, RAM, driver, ou remover o mod a partir desse stack.

O proxy registra para a mesma sessão contrato 65ddebdbecf3a09d57c2fcc3ce39099df539fdf9260181c1e31b90afdc3f9577, silentGearProfile=none, registryShims=[], registryShimPackets=0. O receipt Giselle é separado, exclusivamente minecraft:enchantment, e não supre item_modifier.

Os relógios do cliente e servidor não foram considerados sincronizados. Não se mede tempo entre máquinas subtraindo timestamps de parede. O avanço é de cobertura da inicialização, não de duração da sessão.

## Causa no ProtocolObelisk

A base HF6 já embute ForbiddenArcanusItemModifierRegistry, com seis entradas e 2747 bytes, SHA-256 bd73a8eb99de591e7bbcf2a16ffef24e69d17f30a6bdcbd691b4dc02c2d26db3. A inicialização do proxy mostra o shim armado. Porém RegistryShimCatalog.selectForProfile retorna lista vazia quando não há perfil revisado nem contrato exato 8.1. O contrato 8.2 observado não passa nesse ramo, mesmo com F&A presente.

Foi reproduzido com as classes reais do JAR HF6: seleção vazia no cenário 767/sem perfil/contrato observado. Na HF7, o mesmo cenário seleciona somente o pacote F&A.

## Evidência externa separada

Fonte oficial consultada via GitHub, commit f62af610d550e0d033f6c5cd166e40062638c44b, versão declarada 2.6.1 e MC 1.21.1. Paths e Git blob SHA-1 em evidence/reviewed-upstream.json.

- PlayerMixin lê holderOrThrow(MAGNETIZED) como argumento de hasModifier durante aiStep; o lookup acontece antes de concluir se o item possui modificador. Portanto retirar botas não corrige uma tabela ausente.
- ModItemModifiers define os seis nomes e os predicates/cores/campos usados no pacote existente.
- ItemModifier.DIRECT_CODEC confirma os campos predicate, incompatible_items, incompatible_enchantments, components_to_remove e display.
- FARegistries define a chave item_modifier; NetworkEvents registra cinco payloads clientbound sob a implementação do mod, versão de rede 1.0.
- O construtor do mod registra CLIENT/COMMON, não um SERVER TOML cuja inclusão resolveria esse lookup.

Isto é revisão de fonte da versão correspondente, não execução/comparação SHA do JAR instalado. A busca web não forneceu evidência específica melhor que os arquivos oficiais lidos pelo conector. Downloads de arquivo externo não foram usados no build.

## Delta implementado

Uma classe de produção existente alterada: RegistryShimCatalog. Uma classe auxiliar nova: Atm10Normal82ArcanusEvidence. Manifesto/descriptor atualizados e marcador HF7 adicionado. Nenhum byte de definição de registry foi modificado.

O ramo novo só é considerado depois dos ramos de perfis existentes, preservando sua precedência. Exige protocolo 767, contrato observado exato e namespace forbidden_arcanus. Seleciona apenas o shim permitido pelo operador e valida identidade do shim, namespace, registry ID, seis entradas, 2747 bytes, SHA declarado e SHA recomputado do corpo. Candidatos conflitantes, duplicados ou adulterados são recusados; não se substitui dado errado por placeholders.

O conjunto completo de seis entradas é o registry já revisado de F&A 2.6.1, incluindo aquatic usado por outros métodos do mesmo PlayerMixin. Não foram adicionados outros registries modded nem receitas da 8.1. Admissão não depende desse hash; ele controla somente esta entrega. Não são prometidos autoatendimento de variantes futuras nem detecção remota da versão real de um JAR.

## Transporte preservado

O caminho já existente injectDynamicRegistryShims chama o seletor usando o protocolo negociado e o contrato estrutural da sessão. Na execução 8.2 analisada, esse contrato corresponde ao observado sem normalização. O sender existente injeta o tail após os registries Paper e antes da conclusão da configuração, com fence de identidade da sessão/ciclo e receipt após escrita concluída. O patch não altera esse sender, timer, pipeline, cache, roteamento, registros congelados, mapa de blocos, readiness Paper, ou relay de backend.

Essa descrição foi conferida no código existente. A execução real de Netty/Velocity não foi realizada; os testes de fence são testes unitários da classe real, não uma conexão TCP simulada.

## Resultado dos testes executados

- javac 21.0.11, --release 21 -encoding UTF-8 -g -Xlint:all -Werror: PASS.
- Harness registry: 50 verificações na base HF6; 62 na HF7; -Xverify:all; zero falhas nas execuções finais.
- Harness herdado de configuração: 105 verificações na HF6 e 105 na HF7; conteúdo, nomes, ordem e opt-out preservados.
- Decoder Python independente: VarInts canônicos, root NBT anônimo, todas as seis entradas, ausência de duplicatas/trailing bytes e todos os predicates/cores/campos comparados com as definições revisadas: PASS. Não é o codec de Minecraft.
- Testes negativos: protocolo legado/outros modernos, contrato ausente/parcial/alterado, namespace errado/ausente, opt-out, duplicatas, colisão de registry, metadados incorretos e corpo adulterado com hash declarado correto.
- Isolamento: perfil TTS existente tem precedência; perfil 7.3 com dynamic registries mantém sua transação original; catálogo e NeoVitae paired tags da 8.1 mantidos; pacote Giselle mantido; receipts F&A+Giselle não liberam recipeLifecycle.
- ABI pública/privada de RegistryShimCatalog: idêntica. BridgeConfig.class é byte a byte idêntica à HF6.
- JAR: 1016 entradas preexistentes idênticas; três alteradas (classe, manifesto e descriptor); duas novas (helper e marker); nenhuma removida ou duplicada; CRC íntegro. 746 entradas de recursos preexistentes inalteradas.

Contagens são assertions focadas, não sessões independentes do jogo. O build compila somente as classes do delta contra o JAR base pinado; não é um rebuild Gradle de toda a árvore histórica.

## Integridade e reprodução

Base HF6 SHA-256: c43a58a634d02a2b877dfb3d01c021d7091e6e90d6a418b2deaca5dd6297c9e6.
HF7 SHA-256: f10a1429d7c549e6ad70ea64848bc93dc05ad4f81026ac22a128327aff892f26.

Com o ZIP de fontes extraído, Python3 e JDK21:

```sh
python3 tools/hf7-arcanus/build_hf7.py /caminho/HF6-original.jar /caminho/saida-nova
```

A saída não pode existir. O script recusa base diferente, compila sem rede, executa testes sobre base/candidata e audita bytes, assinaturas, dados e recursos. Reprodutibilidade independente será registrada em evidence/reproducibility.json.

## Limites / não executado

Não executados: cliente Minecraft, codec real de F&A/NeoForge, sessão real Velocity/Paper, suíte completa JUnit/Netty, permanência/inventário/JEI/menus/transições. Não houve acesso ao PC, substituição no proxy ou reinício do servidor. A restrição de cota anterior do Desktop Commander foi respeitada sem nova tentativa.

O registro F&A fica suprido apenas se a regra específica corresponder e a escrita concluir. Isso pode revelar outro registry/configuração ausente. O log HF6 ainda declara BlockState 8.2 não revisado e recipeLifecycle WITHHELD; ambas são pendências não corrigidas. O HotbarGuard auxiliar permanece fora do delta. Homologação global da 8.2 não foi realizada.
