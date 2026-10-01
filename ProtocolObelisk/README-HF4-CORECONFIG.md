# ProtocolObelisk — HF4 CORECONFIG CANDIDATE

Data da investigação: 27/09/2026 (America/Sao_Paulo).

## Leia antes de instalar

Esta candidata corrige UMA omissão comprovada no fallback da HF3: o arquivo de configuração de servidor do próprio NeoForge, `neoforge-server.toml`, não integrava o catálogo básico enviado ao cliente quando nenhum perfil estrutural era selecionado.

**Não é uma declaração de correção definitiva do crash.** A exceção do relatório de 27/09/2026 às 22:58:03 ocorre enquanto NeoForge trata outra exceção de tick de entidade. Corrigir a configuração ausente pode revelar um novo relatório `Ticking entity` com a causa original; não há evidência suficiente no crash anexado para nomear o mod ou a entidade que lançou a primeira exceção.

Nenhum mod client-side é necessário. Nenhum arquivo do servidor em produção foi alterado por este trabalho. O Paper companion permanece como está.

## Base recuperada e identidade

Base exata: `ProtocolObelisk-Velocity-1.9.16-EVOLUTION-ATM10-8.2-HF3-CANDIDATE.jar`.
SHA-256 da base: `353590fd581b69a17bba4bb5a0cc4dad288428762a1940d743bfdfc187e52309`.

O registro da Biblioteca mostra a criação da HF3 em 22/09/2026 às 23:16 UTC, equivalente a 20:16 no horário de São Paulo. A identidade do arquivo recuperado foi conferida; a instalação atualmente carregada no proxy não foi conferida.

Nova candidata: `ProtocolObelisk-Velocity-1.9.16-EVOLUTION-ATM10-8.2-HF4-CORECONFIG-CANDIDATE.jar`.
SHA-256: `22e378a729398eb96aa05eee0338d9e2ee4102aa610f379acedb5255d4c333e4`.

O descritor `velocity-plugin.json` e o manifesto identificam a HF4. Existe também `META-INF/protocolobelisk-hf4-coreconfig.json`. Mensagens históricas hardcoded em classes não alteradas podem continuar mencionando a linha 1.9.16-EVOLUTION; use o SHA-256 e o descritor para distinguir as builds.

## Instalação controlada

1. Pare o Velocity e guarde o JAR atual FORA da pasta `plugins`. Preserve também uma cópia da configuração atual.
2. Deixe somente o JAR HF4 desta linha principal do ProtocolObelisk na pasta `plugins` do Velocity, substituindo o HF3. Não remova outros plugins independentes da família ProtocolObelisk.
3. Não substitua o companion do Paper e não instale este JAR na pasta de mods do cliente ou do backend NeoForge/Youer.
4. Mantenha sua configuração atual. Com `enable-built-in-atm-server-configs=true` — padrão dessa linha — o novo nome entra na lista efetiva em memória mesmo em arquivos v4 antigos. A build não regrava uma configuração v4 existente para fazer essa inclusão.
5. Inicie o Velocity normalmente. Não use hot reload para esta troca de plugin de protocolo. Feche e abra o cliente para um teste frio, sem herdar o estado de uma sessão anterior.

Se você desativou expressamente `enable-built-in-atm-server-configs`, a escolha é respeitada: a correção não força sua ativação. Nesse caso, adicione `neoforge-server.toml` à lista **existente** `transient-server-configs`, uma vez só, preservando os outros nomes. Não substitua toda a lista por esse único nome. Não é necessário criar um TOML de NeoForge no Paper.

A lista efetiva tem limite de 128 nomes. Configurações personalizadas que já ocupavam exatamente o limite precisam ser revisadas, pois a inclusão do nome novo pode ultrapassá-lo. Os validadores de limite, duplicação e caminhos não foram afrouxados.

Uma configuração nova gerada do zero continua com `enabled=false`, como na HF3. Não apague a configuração operacional para instalar a candidata.

## O que a mudança faz

A classe `BridgeConfig` inclui `neoforge-server.toml` no início do conjunto básico revisado. O pipeline de CONFIGURATION já existente transmite um `neoforge:config_file` com esse nome e TOML vazio, usando a inicialização por valores padrão da spec do cliente. O payload codificado tem 22 bytes, sem contar o envelope externo de custom payload.

Nenhuma opção de remoção de entidades é habilitada. Não se injeta `removeErroringEntities=true`, não se captura/descarta exceções do cliente e não se remove NPC, entidade, mod ou configuração em disco.

Não foi habilitada a derivação genérica de nomes para clientes sem perfil: isso poderia introduzir outras configurações não verificadas. O catálogo completo de 288 configurações da ATM10 8.1 continua exclusivo de seu contrato validado; ele substitui o baseline e mantém exatamente a ordem e os payloads existentes. Esta alteração não fornece um catálogo completo revisado para ATM10 8.2.

## Validação realizada

- Compilação da classe alterada: Java 21, `--release 21 -encoding UTF-8 -g -Xlint:all -Werror`, usando o JAR HF3 real como classpath, sem stubs de plataforma.
- Harness executável: 50 verificações focadas por execução, contra a HF3 real e contra o JAR HF4 final, com `-Xverify:all`.
- Nova execução independente do processo de build/teste: JAR byte a byte idêntico.
- Assinaturas Java da classe alterada: idênticas às da HF3.
- Única classe funcional alterada: `br/com/atmbrasil/lobby/velocity/BridgeConfig.class`.
- Metadados existentes alterados: `velocity-plugin.json` e `META-INF/MANIFEST.MF`.
- Novo metadado: `META-INF/protocolobelisk-hf4-coreconfig.json`.
- 1.013 entradas preexistentes do JAR mantidas byte a byte; nenhuma removida.
- 295 arquivos sob `configuration-profiles/atm10-normal-8.1-neoforge-21.1.249/` mantidos byte a byte, incluindo as 288 configurações.
- Catálogo 8.1 revalidado pelo carregador real da biblioteca: 288 payloads, 651.792 bytes codificados, mesmo hash agregado.
- Somente o campo `transientServerConfigs` mudou no snapshot dos valores padrão da configuração.

O harness testa planejamento, configuração, persistência do arquivo de operador, opt-out, deduplicação, limites, path safety, codificação do payload, isolamento de catálogos e preservação da evidência estreita Giselle HF3. **Ele não executa um cliente NeoForge nem o login completo.** As 50 verificações não são 50 tentativas de login e não equivalem à suíte JUnit/Netty inteira.

## Validação ainda pendente

A suíte completa JUnit/Netty não foi executada. A obtenção das dependências binárias externas falhou neste ambiente. O acesso ao PC foi bloqueado pela cota do Desktop Commander, portanto não houve inspeção da instalação viva, alteração remota, reinício do proxy ou teste com Minecraft.

A causa original do tick de entidade continua não identificada. Para homologar:

- Testar entrada fria ATM10 8.2 -> lobby. Conferir nos logs a presença de `neoforge-server.toml` na lista efetiva. O baseline padrão passa de 16 para 17 nomes; configurações de operador podem gerar outras contagens.
- Se houver novo crash, guardar o relatório completo, `logs/latest.log` do cliente e o trecho do log do Velocity da mesma conexão. Um erro diferente não é prova automática de regressão: a HF4 pode ter apenas deixado de mascarar a causa original.
- Antes de promover, testar permanência, inventário/JEI, hotkeys, NPC/menu, lobby -> backend -> lobby, além do cliente 8.1 e da rota legada utilizada na rede.
- Se houver regressão, pare o Velocity, remova a candidata e restaure o JAR anterior. Como não há migração de configuração implementada nesta alteração, não há formato novo a reverter; qualquer edição manual do operador deve ser revertida separadamente.

## Reprodução

Use Java 21 e o JAR HF3 cujo hash é indicado acima:

```text
python tools/hf4-coreconfig/build_hf4.py CAMINHO/ProtocolObelisk-Velocity-1.9.16-EVOLUTION-ATM10-8.2-HF3-CANDIDATE.jar DIRETORIO_NOVO_DE_SAIDA
```

O diretório de saída não pode existir. O script rejeita outra base, compila uma única classe, aplica o overlay, executa o harness contra ambos os JARs e audita o delta. Não baixa dependências, não executa Gradle, não acessa o servidor e não publica nada.

O versionamento Gradle histórico dos fontes foi preservado. Para reproduzir **este artefato HF4 com seu descritor e overlay**, use o script acima; executar simplesmente o build histórico não reproduz o empacotamento HF4.

`tools/hf4-coreconfig/HF3-to-HF4.patch` registra a mudança de produção e as expectativas atualizadas dos testes JUnit existentes. Esses testes JUnit atualizados não foram executados; a evidência executada está no harness independente e em `evidence/` do pacote de release.
