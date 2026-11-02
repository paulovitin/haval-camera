# Haval Camera: controles locais, Wi-Fi e atualização

## Intenção

O Haval Camera deve continuar independente do EcoTrip e do Haval Impulse. O motorista poderá usar o app sozinho na multimídia, compartilhar gravações pelo Wi-Fi ativo e instalar versões publicadas no GitHub com confirmação explícita.

## Objetivos

- Usar somente o endereço IPv4 privado da rede Android ativa com transporte `TRANSPORT_WIFI` para o portal local.
- Exibir um botão **Voltar** nas telas de câmera, gravações, reprodução e configurações. O botão físico continuará funcionando.
- Consultar uma release do repositório oficial, mostrar versão atual e nova, baixar o APK e abrir o instalador Android somente após confirmação.
- Trocar a visão pelas teclas do volante conforme o contrato do usuário: `+` frontal, `−` traseira, `←` retrovisor esquerdo e `→` retrovisor direito.
- Manter botões equivalentes na tela para uso sem volante e para diagnóstico.

## Não objetivos

- Instalação silenciosa, token do GitHub, servidor público ou acesso por rede celular, Ethernet ou Tailscale.
- Dependência obrigatória do EcoTrip ou do Haval Impulse.
- Alterar o caminho nativo de captura antes de provar como o firmware seleciona cada visão.
- Declarar as quatro câmeras funcionais sem validação no carro.

## Restrições observadas

- O compartilhamento atual escolhe o primeiro IPv4 privado e exige interface com nome iniciado por `wlan`. Isso rejeita Wi-Fi válido no firmware do carro.
- A captura atual fixa QCarCam `input_id=4`. O projeto original documenta uma imagem composta e não documenta lentes separadas.
- Um teste `getevent` não observou eventos EV_KEY das teclas do volante. O firmware pode entregar eventos por outra camada.
- Android 9 exige APK assinado pelo mesmo certificado para atualizar a instalação existente.
- A gravação física ainda falha após o primeiro quadro com `Muxer is closed`; este defeito permanece separado desta entrega.

## Abordagens consideradas

### 1. App independente com APIs Android e adaptador comprovado do veículo — escolhida

`ConnectivityManager` fornece a rede Wi-Fi ativa e `LinkProperties` fornece o IPv4. `dispatchKeyEvent` atende teclas Android quando presentes. Uma investigação curta identifica o canal e os IDs reais usados pelo firmware para selecionar as quatro visões. A UI oferece os mesmos quatro comandos.

Vantagens: funciona sem EcoTrip, mantém segurança local e não inventa IDs de câmera. Custo: a troca pelo volante depende de calibração física antes da implementação final.

### 2. Repassar comandos pelo Haval Impulse

Reaproveitaria integração veicular existente, mas criaria dependência para usuários que não possuem o app. Rejeitada.

### 3. Simular quatro câmeras recortando a imagem composta

Funcionaria sem integração veicular, mas não representa as lentes solicitadas e perderia resolução. Rejeitada.

## Desenho

### Rede local

Uma classe pequena consulta `ConnectivityManager.getActiveNetwork()`, exige `NetworkCapabilities.TRANSPORT_WIFI` e escolhe um IPv4 privado não loopback em `LinkProperties`. O servidor liga somente nesse endereço. A validação por prefixo de nome de interface será removida. A tela informa transporte e endereço usados ou um erro específico.

### Navegação

Cada Activity terá **Voltar** visível em posição constante. Na câmera principal, **Voltar** encerra somente a tela e libera a visualização. Em telas filhas, volta à tela anterior. Nenhum botão mata gravação automática ativa.

### Atualização

Configurações terá **Verificar atualização**. O app consulta um manifesto JSON versionado publicado como asset da release estável mais recente de `rafaelcs28/haval-camera`; uma opção separada, desativada por padrão, permite prereleases de teste. O manifesto define `versionCode`, `versionName`, nome exato do APK, tamanho e SHA-256. Release sem manifesto válido não será oferecida. Se houver `versionCode` maior, a tela mostra versão, tamanho e notas curtas. Após confirmação, baixa para cache, valida tamanho, SHA-256 e o certificado fixado `5efec0f94d84d90e769635a81d51e2f25953d5d5b3cea950a115705d3b5e83e0`, então abre o instalador Android. Falha de validação apaga o arquivo e bloqueia instalação. A publicação gera o manifesto a partir do APK verificado, sem entrada manual duplicada.

O app não inicia instalação enquanto captura ou gravação estiver ativa. Ele explica que a atualização reinicia o app, oferece encerrar a visualização e aguarda a gravação ficar ociosa antes de abrir o instalador.

### Seleção de câmera

O contrato de domínio terá a fonte inicial `OEM_DEFAULT` e quatro fontes selecionáveis: `FRONT`, `REAR`, `LEFT_MIRROR`, `RIGHT_MIRROR`. No primeiro uso, o app preserva a visão composta atual. Depois de uma seleção comprovada, persiste e restaura a última fonte. Teclas e botões chamam o mesmo seletor:

- `+`: `FRONT`
- `−`: `REAR`
- `←`: `LEFT_MIRROR`
- `→`: `RIGHT_MIRROR`

Primeiro será registrado, no carro parado, qual evento Android ou serviço veicular recebe cada tecla e qual comando comprovado muda cada visão. Se o firmware não entregar as teclas ao app, os botões na tela continuam disponíveis e a limitação será mostrada. Durante gravação, uma troca fecha o segmento atual e inicia outro, evitando misturar formatos no mesmo MP4.

## Segurança e falhas

- Portal permanece temporário, somente leitura, pareado e preso ao Wi-Fi ativo.
- Atualização nunca instala silenciosamente e nunca aceita assinatura diferente.
- Falha ao trocar câmera mantém tela sem imagem antiga e permite reconectar.
- Nenhuma chave, credencial, APK ou log do veículo entra no Git.

## Validação

- Testes unitários para seleção da rede ativa, rejeição de outros transportes, mapeamento das quatro teclas, comparação de versão e validação de checksum/assinatura.
- Build e assinatura somente na fila autorizada do Mac Mini.
- No carro parado: testar Starlink Mini com celular na mesma rede, botão Voltar, confirmação de atualização, quatro botões na tela e quatro teclas do volante.
- Gravação e troca de segmentos só serão declaradas funcionais após corrigir e validar separadamente o erro atual de captura.

## Decisões restantes do construtor

- Identificar por evidência o canal das teclas e o comando real de seleção das quatro visões.
- Usar componentes já presentes no projeto; não adicionar biblioteca HTTP apenas para GitHub ou download.
