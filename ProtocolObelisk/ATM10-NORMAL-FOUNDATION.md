# ATM10 Normal 7.3 — perfil de lobby revisado

## Escopo

Esta beta trata exclusivamente a entrada do cliente **All the Mods 10 7.3** no
lobby Paper/Purpur vanilla por meio do Velocity. O servidor inicial continua
sendo `lobby`, conforme `velocity.toml`. A troca posterior por
`/server atm10-normal-1` não faz parte desta homologação.

ProtocolObelisk permanece o único motor de compatibilidade em produção. O
addon Profile Capture foi usado apenas como instrumento passivo de evidência e
não é necessário para instalar ou testar esta beta.

## Identidade exata do cliente

O perfil só é selecionado quando as três condições abaixo coincidem:

| Evidência | Valor |
|---|---|
| Minecraft | protocolo 767 / 1.21.1 |
| contrato completo | `cfce57a5a93240f97d570e952c3b4d71e5fac1f11504289f5ea4265371da559a` |
| contrato Silent Gear 4.2 | `003a1f69d13a92e3a9d6c70288dc38e6fc1e29c0c8df4513cbb06653736e0d44` |

O query bruto observado possui 103.124 bytes e 2.381 declarações. O
identificador inválido `ae2:` é ignorado de forma determinística; os 2.380
canais válidos formam a assinatura canônica. Assinatura incompleta, versão
diferente ou ambiguidade continuam falhando fechado antes de qualquer perfil
ser enviado.

## Autoridade dos dados do servidor

Os bytes server-side não foram deduzidos do manifest do CurseForge nem copiados
do ATM10 To The Sky. Eles foram exportados em um boot limpo do ServerFiles
oficial do ATM10 7.3, NeoForge 21.1.247, depois do carregamento dos datapacks:

- transação completa de frozen registries;
- registries dinâmicos contendo somente IDs modded e seus NBTs completos;
- três mapas Silent Gear 4.2 (`traits`, `materials`, `parts`);
- contrato CONFIGURATION e bootstrap neutro do Mekanism.

Cada recurso é limitado, possui tamanho/contagem declarados e SHA-256, e é
validado durante o boot do Velocity. O perfil inteiro é imutável e ligado à
assinatura completa do cliente 7.3.

## CONFIGURATION específica do pack

Além dos canais NeoForge já usados pelo perfil TTS, o cliente 7.3 exige nove
registros CONFIGURATION não opcionais. O código e os artefatos oficiais foram
auditados: oito são apenas registros; o único payload dessa fase é
`mekanism:batch_security@10.7.19`, cujo estado neutro do lobby são dois mapas
vazios, codificados como `00 00`. Nenhum ACK é inventado ou aguardado.

O handshake Owo permanece desativado no pack de produção e, por isso, os canais
`owo:handshake` e `owo:handshake_off` são registrados mas não recebem payload
sintético.

## Compatibilidade e isolamento

O perfil legado ATM10 To The Sky 2.0.2 continua separado, usando Silent Gear
4.1.3 e seus 91 recursos originais byte a byte. O ATM10 Normal usa Silent Gear
4.2 e outro conjunto de frozen/dynamic registries; nenhum blob é compartilhado
por mera semelhança de versão.

ATM10 Normal 7.2 e 8.0 não são aliases do perfil 7.3. A 1.6.0-PLUS preserva
o perfil próprio de 8.0, incorporado depois da captura integral e do export server-side;
os recursos e a chave canônica de 7.3 permanecem independentes. Este documento
continua sendo a evidência histórica específica de 7.3.

## Teste histórico do perfil 7.3

1. mantenha `try = ["lobby"]` e o forced-host atual no `velocity.toml`;
2. substitua os dois JARs ProtocolObelisk e faça cold restart do Velocity e do
   Paper;
3. conecte diretamente com o cliente ATM10 Normal 7.3;
4. confirme entrada no mundo, caminhada, inventário/JEI e hotkeys no lobby;
5. permaneça conectado por alguns minutos;
6. nesta rodada, não use `/server atm10-normal-1`;
7. repita uma entrada com ATM10 To The Sky 2.0.2 para regressão.

Se houver falha, preserve o log completo do Velocity, do Paper e o `latest.log`
do cliente desde o início da conexão. Um crash report isolado não substitui a
sequência da negociação.
