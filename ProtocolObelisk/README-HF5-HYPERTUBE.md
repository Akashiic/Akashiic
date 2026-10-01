# ProtocolObelisk — HF5 HYPERTUBE CANDIDATE

**Estado: candidata de correção, não homologada em Minecraft.**

Corrige a omissão de `create_hypertube-server.toml` no catálogo básico de configurações transitórias usado no lobby Paper. Preserva a inclusão de `neoforge-server.toml` da HF4. O incidente de referência é o crash de 27/09/2026, em Create Hypertube 0.6.0 / NeoForge 21.1.251.

## Instalação

1. Pare o **Velocity**. Guarde o JAR HF4 e uma cópia do `bridge.properties` fora da pasta `plugins`.
2. Substitua somente o JAR principal do ProtocolObelisk no Velocity por `ProtocolObelisk-Velocity-1.9.16-EVOLUTION-ATM10-8.2-HF5-HYPERTUBE-CANDIDATE.jar`. Não carregue duas versões do plugin principal simultaneamente.
3. Mantenha o companion Paper, os mods do cliente e os plugins auxiliares existentes. Não crie um arquivo do Hypertube no Paper e não copie uma configuração de outro pack para o cliente.
4. Inicie o Velocity normalmente, sem hot reload. Feche e abra o cliente antes do teste de entrada.

O log do gerenciador de plugins deve mostrar `1.9.16-EVOLUTION-ATM10-8.2-HF5-HYPERTUBE-CANDIDATE`. Com `enable-built-in-atm-server-configs=true`, a inclusão é automática na lista efetiva em memória; não é necessário apagar ou substituir o `bridge.properties` existente. A lista observada no teste HF4 tinha 19 nomes. Mantida aquela configuração, a HF5 deve preparar 20, incluindo `create_hypertube-server.toml`.

**Opt-out preservado:** se o operador desativou `enable-built-in-atm-server-configs`, a build não ignora essa escolha. Para usar a inclusão manual, acrescente `create_hypertube-server.toml` à propriedade existente `transient-server-configs`, separado por vírgula e sem retirar os demais nomes. Reinicie o Velocity após editar.

## O que a correção faz

Utiliza o transporte de CONFIGURATION já existente para enviar o nome revisado com conteúdo TOML vazio. A intenção é carregar, em memória, os padrões declarados pelo próprio mod do cliente antes do tick de entidades. Não injeta um modo de viagem alternativo, não habilita remoção de entidades e não modifica os arquivos locais de configuração do cliente por este patch.

Não altera registries, mapas BlockState, receitas, roteamento, código do Paper ou caminho legado protocol-5. Não exige mod client-side adicional. A classe funcional alterada em relação à HF4 é somente `BridgeConfig`.

## Validação e limites

Compilação Java 21 estrita; 87 verificações focadas contra o JAR HF5; 86 verificações de comparação contra o JAR HF4; auditoria das entradas ZIP; ABI pública/privada preservada; reconstrução independente byte a byte idêntica. Evidências incluídas em `evidence/`.

Não foram executados Minecraft, Velocity/Paper em execução real, o binário do Hypertube nem a suíte completa Netty/JUnit. As verificações são de configuração, planejamento e codificação com as classes reais do ProtocolObelisk. Não equivalem à homologação de todos os recursos de ATM10 8.2.

No incidente, o mapa BlockState da 8.2 estava indisponível e o ciclo de receitas estava retido por falta de evidência estrutural revisada. A HF5 não desativa esses controles. O erro independente do plugin `protocolobelisk-hotbarguard 1.0.0` também não é corrigido por este JAR.

## Teste e rollback

Teste primeiro a entrada e permanência no lobby. Depois, inventário/JEI, menus, hotkeys e transição ao backend, registrando os resultados separadamente. Se houver novo crash, preserve o novo relatório, `latest.log` do cliente e logs do proxy/lobby da mesma tentativa.

Para reverter: pare o Velocity, retire a HF5, recoloque a HF4 preservada e inicie novamente. Restaure o `bridge.properties` somente se ele tiver sido alterado manualmente. A HF4 continuará sujeita à omissão conhecida; rollback não significa resolução do crash.

## Integridade

SHA-256 do JAR HF5:

`e90274a5f813b94b44fb1ca517919cab06a59d9db24b3204793b7b7dd3593a65`

O pacote contém o JAR, fontes e ferramenta reproduzível, este guia, diagnóstico e evidências. Os arquivos `evidence/*bridge.properties` são fixtures de teste, **não** substitutos para sua configuração operacional.

## Entrada de build desta candidata

Use `tools/hf5-hypertube/build_hf5.py` com o JAR HF4 original, Python 3 e JDK 21.

```sh
python3 tools/hf5-hypertube/build_hf5.py /caminho/HF4.jar /caminho/saida-nova
```

Documentos e ferramentas HF4/anteriores são históricos. O build HF5 preserva as classes não alteradas do JAR original e testa a configuração/planejamento/codec; não recompila e homologa a rede inteira. Consulte o diagnóstico da distribuição para as limitações.
