# Changelog — 1.9.16-EVOLUTION

Base: 1.9.15-EVOLUTION, candidata com admissão real validada pelo usuário.

- Corrigida a preservação do canal de transporte BungeeCord no filtro moderno
  de REGISTER/UNREGISTER do lobby, mantendo o limite de 96 canais e a autoridade
  nativa de rota do Velocity.
- Protegida a propriedade do canal BungeeCord contra seleção ou reserva como
  sink do Obelisk por anúncios modded ou configurações antigas.
- Corrigida a decisão final do ciclo de receitas para utilizar a normalização
  estrutural revisada. Configurações, registries, tags e bootstraps exigidos
  continuam sujeitos aos comprovantes de entrega.
- Aprimorados os diagnósticos de conclusão e retenção do ciclo de receitas.
- Acrescentados diagnósticos limitados de mudanças nos controles de movimento
  e teletransportes no Paper, opt-in pelo `debug` existente.
- Acrescentados testes de regressão para os caminhos corrigidos, incluindo
  recusas de liberação de receitas quando faltam evidências e preservação dos
  limites do filtro de registros.
- Incluídos os menus ATM Brasil e Forbidden com verificação de disponibilidade
  antes da ação BungeeCord. O destino Pokezuca permanece fora deste pacote por
  ainda não estar configurado no Velocity fornecido.

## Pendências

- A causa do pulo ainda não foi demonstrada; os diagnósticos não são uma
  correção de gravidade, força de salto ou velocidade.
- Não se declara eliminado o custo de inicialização do JEI no cliente.
- Os avisos sobre `twilightforest:glacier` não recebem um bioma aproximado.
- A validação com cliente real da 1.9.16 deve confirmar clique no seletor,
  receitas/inventário, primeira entrada e retorno ao lobby.
