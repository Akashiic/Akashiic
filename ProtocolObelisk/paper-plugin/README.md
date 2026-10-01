# ProtocolObelisk Paper 1.9.16-EVOLUTION

O companion Paper protege o lobby vanilla enquanto o Velocity permanece como unica autoridade de
roteamento. Ele nao escolhe destinos e nao executa transferencias de backend.

## Comando administrativo

Use apenas `/protocolobelisk reload|status`. O alias `/atm10lobby` foi removido para nao capturar
comandos ou acoes de menus que pertencem ao seletor de servidores. As permissoes legadas
`atm10lobby.admin` e `atm10lobby.build` continuam aceitas para compatibilidade de instalacoes.

## Protecoes de interface

`safety.cancel-generic-ui-events` controla o cancelamento abrangente de `PlayerInteractEvent`,
interacoes com entidades/armor stands, `InventoryOpenEvent`, `InventoryClickEvent` e
`InventoryDragEvent` para visitantes. O padrao e
`false`, inclusive quando a chave nao existe em um `config.yml` v3/v4 ja instalado. Esse padrao
permite que ProMenus e seletores baseados em item/inventario recebam abertura e clique normalmente.

Defina a chave como `true` somente se o lobby nao depender dessas interfaces e precisar reproduzir
o bloqueio abrangente anterior. O valor deve ser booleano; outro tipo faz a recarga falhar sem
substituir a configuracao ativa.

Quebra/colocacao de bloco, buckets, dano e outras protecoes mutativas continuam
independentes dessa chave e respeitam `safety.protect-entire-server`, o mundo configurado e o
bypass `protocolobelisk.build` (ou o legado `atm10lobby.build`). Com a chave `false`, contudo,
portas, alavancas, containers e manipulacao de armor stands podem ser permitidos para nao bloquear
menus/NPCs; use protecao externa quando o mapa expuser esses objetos a visitantes.

## Diagnostico de movimento (1.9.16)

O diagnostico usa a chave existente `debug: true` no `config.yml` do Paper. Depois de
`/protocolobelisk reload`, reconecte o jogador para iniciar uma sessao observada. Com debug
false nao ha tarefa de amostragem; desligar debug limpa as sessoes e cancela a tarefa.

Uma unica tarefa sincronizada executa a cada 20 ticks. Cada passagem inspeciona no maximo
64 jogadores em fila rotativa, durante os primeiros 300 segundos desde JOIN, sem depender de
READY ou de um evento de autenticacao do nLogin. Com mais de 64 jogadores em observacao,
a frequencia individual diminui. O tempo da janela e monotonicamente medido; lag nao o estende.

A primeira amostra (`BASELINE`) e mudancas dos controles (`CONTROLS_CHANGED`) geram snapshots.
A comparacao considera velocidades, atributos, permissao de voo, modo de jogo, gravidade e
nomes/amplificadores de efeitos. Posicao, velocidade vetorial, postura e duracao de efeitos nao
provocam registros a cada movimento, mas constam no snapshot quando aplicavel. Ha limites de
4096 sessoes, 16 registros por amostragem e 8 registros de teleporte por sessao. JOIN_BEFORE,
JOIN_AFTER, READY e QUIT continuam limitados a um registro de cada fase. Efeitos sao limitados
a 16 e detalhes do snapshot a 2048 caracteres.

Eventos de teleporte sao observados em MONITOR, inclusive cancelados, com causa, origem,
destino solicitado e `sinceJoinMs`. Um evento nao cancelado pode reabrir uma janela de 15 segundos
apos a janela inicial terminar, respeitando os mesmos limites absolutos da sessao. A proxima
amostra recebe `AFTER_TELEPORT`. Esse registro nao e confirmacao de que um teleporte terminou.
O contador `voidRescues` permite correlacionar o resgate ja existente do lobby. Quit limpa os
recursos da sessao; disable limpa todas as sessoes e cancela a tarefa compartilhada.

`authenticationState=unobserved` e intencional: o plugin nao afirma que o jogador se autenticou
por observar restauracao de velocidade ou retirada de BLINDNESS. Correlacione o horario com o
log de autenticacao do proxy. A amostragem pode perder alteracoes transitórias entre passagens,
nao captura pacotes/ACKs e nao identifica automaticamente o plugin que alterou um estado.
A causa dos pulos ainda nao foi demonstrada. Este componente nao altera atributos, gravidade,
velocidade, cancelamento de evento ou destino de teleporte, e nao depende da API do nLogin.

APIs verificadas: [PlayerTeleportEvent Paper 1.21.1](https://jd.papermc.io/paper/1.21.1/org/bukkit/event/player/PlayerTeleportEvent.html),
[PlayerMoveEvent Paper 1.21.1](https://jd.papermc.io/paper/1.21.1/org/bukkit/event/player/PlayerMoveEvent.html),
[BukkitScheduler Paper 1.21.1](https://jd.papermc.io/paper/1.21.1/org/bukkit/scheduler/BukkitScheduler.html).
