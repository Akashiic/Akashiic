# ProtocolObelisk — HF6 CREATE CANDIDATE

**Estado: candidata construída e verificada de forma focada; não instalada nem homologada em Minecraft.**

Corrige a omissão de `create-server.toml` no conjunto básico transitório enviado na entrada do lobby Paper. O incidente é o crash de Create 6.0.10 / NeoForge 21.1.251 em `PlayerMixin.pretendNotPassenger`, após a HF5 ter carregado a configuração do addon Hypertube.

## Instalação

1. Pare o **Velocity**. Preserve o JAR HF5 atual e uma cópia do `bridge.properties` fora de `plugins`.
2. Substitua **somente o JAR principal ProtocolObelisk no Velocity** por `ProtocolObelisk-Velocity-1.9.16-EVOLUTION-ATM10-8.2-HF6-CREATE-CANDIDATE.jar`. Não carregue HF5 e HF6 simultaneamente.
3. Mantenha o companion Paper, os mods do cliente e os plugins auxiliares. Esta correção não requer criar `create-server.toml` no Paper, no proxy ou no cliente.
4. Inicie o Velocity normalmente, sem hot reload. Feche e abra o cliente antes do teste.

Com `enable-built-in-atm-server-configs=true`, a inclusão é automática na lista efetiva em memória. **Não apague nem substitua sua configuração atual.** Mantidas as opções e os nomes dos seus logs HF5, são esperados **21** nomes, iniciando por:

```text
neoforge-server.toml
create-server.toml
create_hypertube-server.toml
```

O número 21 corresponde ao conjunto observado do operador, que inclui dois nomes de XyCraft. Uma configuração nova, desabilitada por padrão, tem 19 nomes; as duas contagens não são contraditórias.

O gerenciador de plugins deve identificar `1.9.16-EVOLUTION-ATM10-8.2-HF6-CREATE-CANDIDATE`. Algumas linhas internas continuam mostrando a versão-base `1.9.16-EVOLUTION`; use a identificação do gerenciador e a lista efetiva para distinguir a candidata.

Se os built-ins tiverem sido desativados expressamente, a escolha será respeitada. A alternativa manual é acrescentar `create-server.toml` à propriedade existente `transient-server-configs`, sem retirar os demais nomes, preferencialmente depois de `neoforge-server.toml` e antes de `create_hypertube-server.toml`; reinicie o Velocity. Não acrescente o nome duas vezes à propriedade.

## O que foi alterado

- A configuração SERVER do Create-base entra antes da configuração do addon Hypertube no baseline revisado.
- O transporte existente envia o nome correto com TOML vazio. O objetivo é inicializar os padrões declarados pelo próprio cliente antes dos ticks; não se usa o conteúdo de outro pack.
- Mantidas as correções HF4 (NeoForge) e HF5 (Hypertube), a deduplicação, os limites e a configuração do operador sem reescrita automática.
- Testes focados verificam a cadeia NeoForge/Create/Hypertube e a decodificação independente dos payloads, para impedir a repetição dessa omissão conhecida.

Não foram alterados registries, BlockStates, autorização, roteamento, tempo de handshake, codecs, política de receitas, código do Paper nem plugins auxiliares. Não há mod client-side adicional e não foi habilitada remoção automática de entidades.

## Validação

Java 21 com `--release 21 -encoding UTF-8 -g -Xlint:all -Werror`. O harness executado com `-Xverify:all` aprovou 105 verificações contra a HF6 e 102 de comparação contra a HF5. São verificações de configuração, planejamento e codificação com classes reais do ProtocolObelisk, **não testes de integração de Minecraft**.

Uma única classe de produção mudou (`BridgeConfig.class`); manifesto e descriptor identificam a versão nova. 1.015 entradas preexistentes permaneceram byte a byte idênticas, sem remoções nem duplicações. ABI pública/privada preservada. O catálogo 8.1 mantém 288 configurações e os mesmos hashes. A reconstrução independente e os gates finais estão em `evidence/`.

Não foram executados Minecraft, o binário do Create/Ponder, Velocity/Paper em sessão real ou a suíte completa JUnit/Netty. Dependências JUnit/Netty não estavam disponíveis neste ambiente. As expectativas de nomes/contagens em `ConfigurationPersistenceTest` foram atualizadas, mas não se declara a suíte JUnit aprovada.

## Limites e próximo teste

Teste primeiro entrada, movimento e permanência no lobby. Só depois valide inventário/JEI, menus, hotkeys e transições. Se houver novo crash, preserve o relatório e logs do cliente/proxy/lobby da mesma sessão.

O contrato 8.2 observado ainda não tem mapa BlockState revisado, e o ciclo de receitas segue retido pela política existente. A ausência do crash do Create não homologará automaticamente essas áreas. A revisão desta HF6 cobre a cadeia de configuração envolvida no incidente; **não é um catálogo completo de todos os SERVER configs de ATM10 8.2**.

O erro independente de `protocolobelisk-hotbarguard 1.0.0` também não é corrigido pelo JAR principal.

## Rollback

Pare o Velocity, retire HF6, recoloque o JAR HF5 preservado e inicie. Restaure o `bridge.properties` apenas se o tiver editado manualmente. A HF5 continua sujeita à omissão de Create já demonstrada; rollback não representa correção do crash.

## Integridade e fontes

SHA-256 do JAR:

`c43a58a634d02a2b877dfb3d01c021d7091e6e90d6a418b2deaca5dd6297c9e6`

Fontes preservadas com build de overlay reproduzível em `tools/hf6-create/build_hf6.py`. A entrada exige o JAR HF5 exato (SHA-256 `e90274a5f813b94b44fb1ca517919cab06a59d9db24b3204793b7b7dd3593a65`), Python 3 e JDK 21. Não é um rebuild Gradle completo: recompila a classe alterada contra o binário-base validado, conserva as demais classes e executa o harness.

```sh
python3 tools/hf6-create/build_hf6.py /caminho/HF5.jar /caminho/saida-nova
```

A pasta de saída não deve existir. As ferramentas HF4/HF5/anteriores são históricas e não são entradas de build desta candidata. Os `evidence/*bridge.properties` são **fixtures de teste, não configurações de produção**.
