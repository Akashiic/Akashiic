# Akashic Baubles Layout Sync

Corrige de forma permanente o desync de slots do **BaublesExpanded** (2.2.x) entre servidor e cliente, que causava:

```
java.lang.ArrayIndexOutOfBoundsException: 17
    at baubles.common.container.InventoryBaubles.func_70299_a(InventoryBaubles.java:137)
    at baubles.common.network.PacketSyncBauble.onMessage(PacketSyncBauble.java:55)
There was a critical exception handling a packet on channel baubles
```

seguido de desconexão (e, no nosso caso, o cliente travado depois disso).

## Causa

O BaublesExpanded monta a lista de slots (`BaubleExpandedSlots.assignedSlots`) **localmente em cada lado**, durante o
preInit, a partir dos mods instalados e das configs. O servidor nunca informa ao cliente qual é o layout dele. Qualquer
diferença (um mod só do servidor, uma config diferente, uma atualização) deixa o cliente com menos slots, ou com os
slots em outra ordem. O primeiro pacote de sync para um slot que o cliente não tem estoura o array, e o FML derruba a
conexão.

Exemplo real: o servidor tinha 20 slots e o cliente 17. Os pacotes dos slots 17, 18 e 19 derrubaram a conexão.

## Correção

1. **Servidor como fonte da verdade.** No início de cada conexão (`ServerConnectionFromClientEvent`, antes do JoinGame
   e antes de qualquer pacote do Baubles), o servidor envia a sua lista de slots. Com Velocity, isso se repete a cada
   troca de backend.
2. **Cliente adota o layout pela duração da conexão.** Substitui a lista de slots e redimensiona os inventários de
   Baubles já em cache. Um tipo que nenhum mod do cliente registrou vira `unknown`: o slot existe, então os índices
   continuam alinhados, mas fica vazio. Ao desconectar, o layout local volta.
3. **Rede de segurança no cliente.** Se mesmo assim chegar um índice fora do inventário, ele é ignorado com um aviso no
   log, em vez de derrubar a conexão. Cobre `setInventorySlotContents`, `decrStackSize`, `getStackInSlotOnClosing` e o
   sync completo (`PacketSyncAllBauble`). No servidor essas proteções não fazem nada, então o comportamento lá continua
   o original.

O mod não muda nada salvo em disco: nem config, nem os dados de Baubles dos jogadores.

## Instalação

Coloque `akashicbaubleslayout-1.0.0.jar` em `mods/` **no servidor e no modpack do cliente**. Requer UniMixins (já
presente nos dois lados).

- Servidor sem o mod: o cliente ainda fica protegido pela rede de segurança (não desconecta), mas os slots extras do
  servidor não aparecem.
- Cliente sem o mod: nada muda para ele (mesmo comportamento de antes). O pacote extra é ignorado.

Logs quando age:

```
[AkashicBaublesLayout]: Adopted the server Baubles layout: 20 slots (this client had 17). Server: [...] Local: [...]
[AkashicBaublesLayout]: Disconnected: restored the local Baubles layout (17 slots).
[AkashicBaublesLayout]: Ignored Baubles slot 19 in setInventorySlotContents (...)   <- só se a sync não ocorreu
```

## Validação feita

Servidor e cliente Forge 1.7.10 de dev, de verdade (cliente via Xvfb), com BaublesExpanded 2.2.22-GTNH. Um mod de
teste (fora do jar) deixava o servidor com 20 slots (incluindo um tipo que o cliente não conhece) e o cliente com 4.
Ao logar, o servidor colocava um item no slot 19.

| Cenário | Resultado |
|---|---|
| Sem o mod nos dois lados (controle) | `ArrayIndexOutOfBoundsException: 19` → desconectado (reproduz o bug) |
| Mod nos dois lados | cliente adota 20 slots, continua conectado, vê o item no slot 19; ao sair volta a 4 |
| Mod só no cliente | slot 19 ignorado com aviso, continua conectado |
| Mod só no servidor | igual ao controle (o cliente precisa do mod); nenhum erro novo do pacote extra |

## Build

```
./gradlew build
```
