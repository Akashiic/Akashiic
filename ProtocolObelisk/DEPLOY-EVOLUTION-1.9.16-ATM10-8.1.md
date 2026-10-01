# Instalação e validação — 1.9.16-EVOLUTION

1. Guarde os JARs 1.9.15 testados e as configurações atuais fora da pasta plugins.
2. Pare completamente o Velocity e o Paper do lobby. Substitua o JAR do Obelisk
   em cada um pelo componente correspondente da 1.9.16. Não use hot-swap/reload
   de plugins para trocar os JARs.
3. Mantenha `bridge.properties`, `config.yml`, `velocity.toml`, autenticação e
   forwarding existentes. O `bungee-plugin-message-channel = true` já estava
   correto no arquivo enviado.
4. Copie os dois arquivos de `integrations/ProMenus/menus/LOBBIES/` para
   `plugins/ProMenus/menus/LOBBIES/` do lobby, preservando os demais menus.
5. Reinicie os dois processos e confira `1.9.16-EVOLUTION` nos logs. O Youer
   não recebe nenhum JAR deste pacote; os clientes não recebem mod obrigatório.

## Verificação desta rodada

- Entre diretamente no lobby com o cliente habitual e com os clientes dos
  amigos reconhecidos pela normalização revisada. Verifique HUD e blocos.
- Nos clientes equivalentes revisados, confirme `recipeLifecycle=RELEASE`;
  confira a aceitação de readiness no Paper e teste inventário/JEI.
- Após autenticar, abra o menu ATM Brasil e clique no destino. Confirme a
  transferência para `atm10-normal-1`, depois retorne por `/server lobby`.
- Compare o clique do menu com o comando `/server atm10-normal-1` se necessário.
  O primeiro usa o transporte BungeeCord; ambos deixam a decisão ao Velocity.
- Observe os pulos na primeira entrada e após o retorno. Para essa investigação,
  use temporariamente o `debug` do Paper e guarde os logs da mesma janela de
  horário. Os registros de mudança de controles não afirmam que houve login
  no nLogin: os horários devem ser correlacionados com o log de autenticação.

Os testes automatizados não executam a interface gráfica do ATM10 nem o clique
real no ProMenus. Uma sessão admitida também não comprova, sozinha, renderização
correta, inventário pronto ou desempenho estável por longos períodos.

## Menus incluídos

Os nomes e endereços dos dois destinos foram mantidos conforme os arquivos do
usuário. Se forem usados em outra rede, ajuste os menus para aquela rede.
Não foi criado `pokezuca-1`: esse destino não existe no Velocity enviado e seu
menu ainda tinha IP/porta provisórios.

## Reversão

Pare os dois processos e restaure os JARs 1.9.15 guardados. O patch não exige
uma migração destrutiva de configuração. Os menus podem ser restaurados do
backup se for necessário comparar a configuração original. A 1.9.15 preserva
as pendências de transferência e receitas registradas na auditoria anterior.
