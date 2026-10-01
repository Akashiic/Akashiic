# Menus da Rede Akashic

Copie `menus/LOBBIES/LOBBY_ATM_BRASIL.yml` e
`menus/LOBBIES/LOBBY_FORBIDDEN.yml` para os mesmos caminhos dentro de
`plugins/ProMenus/` no lobby, durante a atualização dos JARs.

Cada menu recebeu somente a inversão de duas ações: a verificação de servidor
online agora precede a conexão. A sintaxe `[bungee=destino]` já era válida.
Os demais valores, aparência, nomes de destino e endereços foram preservados.

A correção dos menus organiza a verificação de disponibilidade; o transporte
também depende da preservação do canal BungeeCord, corrigida no Obelisk 1.9.16.
Mantenha `bungee-plugin-message-channel = true` no Velocity, conforme seu arquivo.

Pokezuca não foi incluído: `pokezuca-1` não estava cadastrado e o menu enviado
ainda continha campos provisórios de IP/porta.

Documentação oficial consultada:
https://culleystudios.com/docs/spigot-plugins/cs-api/actions/tasks/bungee
https://culleystudios.com/docs/spigot-plugins/cs-api/actions/tasks
https://docs.papermc.io/velocity/dev/plugin-messaging/
