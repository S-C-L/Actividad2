package mx.aprendizaje.actividad2;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Properties;

/** HTML, lógica y acceso a datos en un único servlet, sin capas. */
@WebServlet(name = "ForosServlet", urlPatterns = {"/", "/login", "/foros", "/foros.html"})
public class ForosServlet extends HttpServlet {
    private final SecureRandom random = new SecureRandom();
    private String url;
    private String usuarioBD;
    private String claveBD;
    private String vistaLogin;
    private String vistaForos;

    @Override
    public void init() throws ServletException {
        Properties config = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("base-datos.properties")) {
            if (in == null) throw new IOException("Falta base-datos.properties");
            config.load(in);
            Class.forName("com.mysql.cj.jdbc.Driver");
            url = config.getProperty("db.url");
            usuarioBD = config.getProperty("db.usuario");
            claveBD = config.getProperty("db.clave", "");
            vistaLogin = leerVista("login.html");
            vistaForos = leerVista("foros.html");
        } catch (IOException | ClassNotFoundException e) {
            throw new ServletException("No se pudo configurar la base de datos", e);
        }
    }

    private Connection conectar() throws SQLException {
        return DriverManager.getConnection(url, usuarioBD, claveBD);
    }

    private String leerVista(String nombre) throws IOException {
        try (InputStream in = getServletContext().getResourceAsStream("/WEB-INF/vistas/" + nombre)) {
            if (in == null) throw new IOException("Falta la vista " + nombre);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        preparar(req, resp);
        HttpSession sesion = req.getSession();
        token(sesion);
        boolean autenticado = sesion.getAttribute("usuarioId") != null;
        boolean paginaLogin = "/login".equals(req.getServletPath()) || "/".equals(req.getServletPath());
        if (!autenticado && !paginaLogin) {
            resp.sendRedirect(req.getContextPath() + "/login");
            return;
        }
        if (autenticado && paginaLogin) {
            resp.sendRedirect(req.getContextPath() + "/foros.html");
            return;
        }
        Object aviso = sesion.getAttribute("aviso");
        sesion.removeAttribute("aviso");
        boolean publicacionCorrecta = Boolean.TRUE.equals(sesion.getAttribute("publicacionCorrecta"));
        sesion.removeAttribute("publicacionCorrecta");
        String avisoHtml = aviso == null ? "" : "<p class='aviso' role='status'>" + html(aviso) + "</p>";
        if (!autenticado) {
            resp.getWriter().print(vistaLogin
                    .replace("{{RUTA}}", html(req.getContextPath() + "/login"))
                    .replace("{{CSRF}}", html(token(sesion)))
                    .replace("{{NONCE}}", html(req.getAttribute("nonce")))
                    .replace("{{AVISO}}", avisoHtml));
        } else {
            StringWriter contenido = new StringWriter();
            PrintWriter out = new PrintWriter(contenido);
            String ruta = req.getContextPath() + "/foros.html";
            out.println(avisoHtml);
            out.println("<div class='profile-bar'><details class='profile-menu'>"
                    + "<summary>Bienvenido, " + html(nombreCorto(sesion.getAttribute("nombre"))) + "</summary>"
                    + "<div class='profile-dropdown'>");
            formulario(out, ruta, sesion, "salir");
            out.println("<button class='logout-button' type='submit'>Cerrar sesión</button>"
                    + "</form></div></details></div>");
            out.println("<hr>");
            try (Connection c = conectar()) {
                listaForos(out, ruta, sesion, c);
            } catch (SQLException e) {
                log("Error al consultar foros", e);
                out.println("<p>No se pudo consultar la base de datos. Revisa XAMPP y la configuración de conexión.</p>");
            }
            out.flush();
            resp.getWriter().print(vistaForos
                    .replace("{{NONCE}}", html(req.getAttribute("nonce")))
                    .replace("{{ALERTA}}", publicacionCorrecta
                            ? "<script nonce='" + html(req.getAttribute("nonce"))
                                    + "'>alert('Publicación correcta');</script>" : "")
                    .replace("{{CONTENIDO}}", contenido.toString()));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        preparar(req, resp);
        HttpSession sesion = req.getSession();
        String recibido = req.getParameter("csrf");
        if (recibido == null || !recibido.equals(sesion.getAttribute("csrf"))) {
            resp.sendError(403, "Formulario inválido. Recarga la página.");
            return;
        }
        String destino = req.getContextPath() + "/foros.html";
        String accion = req.getParameter("accion");
        try {
            if ("salir".equals(accion)) {
                sesion.invalidate();
            } else {
                boolean acceso = "registro".equals(accion) || "entrar".equals(accion);
                if (!acceso && sesion.getAttribute("usuarioId") == null) {
                    throw new IllegalArgumentException("Debes iniciar sesión.");
                }
                try (Connection c = conectar()) {
                    if ("registro".equals(accion)) {
                        registrar(req, c);
                        sesion.setAttribute("aviso", "Registro completado. Ya puedes iniciar sesión.");
                    } else if ("entrar".equals(accion)) {
                        entrar(req, c);
                    } else if ("crearForo".equals(accion)) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO foros (creador_id,titulo,descripcion) VALUES (?,?,?)")) {
                            ps.setInt(1, (Integer) sesion.getAttribute("usuarioId"));
                            ps.setString(2, "Comentario de " + nombreCorto(sesion.getAttribute("nombre")));
                            ps.setString(3, texto(req, "descripcion", 5000));
                            ps.executeUpdate();
                        }
                        sesion.setAttribute("publicacionCorrecta", true);
                    } else if ("publicarMensaje".equals(accion)) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO mensajes (foro_id,autor_id,titulo,contenido) "
                                + "SELECT id,?,?,? FROM foros WHERE id=?")) {
                            ps.setInt(1, (Integer) sesion.getAttribute("usuarioId"));
                            ps.setString(2, texto(req, "titulo", 150));
                            ps.setString(3, texto(req, "contenido", 5000));
                            ps.setInt(4, identificador(req, "foroId"));
                            if (ps.executeUpdate() == 0) {
                                throw new IllegalArgumentException("El foro no existe.");
                            }
                        }
                        sesion.setAttribute("publicacionCorrecta", true);
                    } else if ("marcarInapropiado".equals(accion)) {
                        // La autorización se comprueba en la BD, no solo ocultando el botón.
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE mensajes m JOIN foros f ON f.id=m.foro_id "
                                + "SET m.inapropiado=1 WHERE m.id=? AND f.creador_id=? AND m.inapropiado=0")) {
                            ps.setInt(1, identificador(req, "mensajeId"));
                            ps.setInt(2, (Integer) sesion.getAttribute("usuarioId"));
                            if (ps.executeUpdate() == 0) {
                                throw new IllegalArgumentException("No puedes marcar este mensaje: solo el creador del foro puede hacerlo, y debe estar sin marcar.");
                            }
                        }
                        sesion.setAttribute("aviso", "Mensaje marcado como inapropiado. Su contenido está oculto.");
                    } else {
                        throw new IllegalArgumentException("Acción no válida.");
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            sesion.setAttribute("aviso", e.getMessage());
        } catch (SQLException e) {
            log("Error al guardar datos", e);
            sesion.setAttribute("aviso", "No se pudo completar la operación. Revisa la conexión y la base de datos.");
        } catch (Exception e) {
            log("Error de autenticación", e);
            sesion.setAttribute("aviso", "No se pudo completar la autenticación.");
        }
        HttpSession actual = req.getSession(false);
        if (actual == null || actual.getAttribute("usuarioId") == null) {
            destino = req.getContextPath() + "/login";
        }
        resp.setStatus(HttpServletResponse.SC_SEE_OTHER);
        resp.setHeader("Location", destino);
    }

    private void registrar(HttpServletRequest req, Connection c) throws Exception {
        String nombre = texto(req, "nombre", 100);
        String usuario = texto(req, "usuario", 50);
        if (!usuario.matches("[A-Za-z0-9_.-]{3,50}")) {
            throw new IllegalArgumentException("El usuario debe tener de 3 a 50 caracteres: letras, números, punto, guion o guion bajo.");
        }
        String clave = req.getParameter("clave");
        if (clave == null || clave.length() < 8 || clave.length() > 128) {
            throw new IllegalArgumentException("La contraseña debe tener entre 8 y 128 caracteres.");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO usuarios (nombre,usuario,password) VALUES (?,?,?)")) {
            ps.setString(1, nombre);
            ps.setString(2, usuario);
            // Texto plano exclusivamente para la práctica académica.
            ps.setString(3, clave);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) throw new IllegalArgumentException("Ese nombre de usuario ya está registrado.");
            throw e;
        }
    }

    private void entrar(HttpServletRequest req, Connection c) throws Exception {
        String usuario = texto(req, "usuario", 50);
        String clave = req.getParameter("clave");
        if (clave == null || clave.isEmpty() || clave.length() > 128) {
            throw new IllegalArgumentException("contraseña incorrecta");
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT id,nombre,password FROM usuarios WHERE usuario=?")) {
            ps.setString(1, usuario);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Usuario o contraseña incorrectos.");
                }
                String guardada = rs.getString("password");
                boolean valida = guardada != null && clave.equals(guardada);
                if (!valida) throw new IllegalArgumentException("contraseña incorrecta");
                req.getSession().invalidate();
                HttpSession nueva = req.getSession(true);
                nueva.setAttribute("usuarioId", rs.getInt("id"));
                nueva.setAttribute("nombre", rs.getString("nombre"));
                token(nueva);
            }
        }
    }

    private void listaForos(PrintWriter out, String ruta, HttpSession sesion, Connection c) throws SQLException {
        out.println("<h2><strong>Comentarios</strong></h2>");
        formulario(out, ruta, sesion, "crearForo");
        out.println("<p><textarea name='descripcion' aria-label='Comentario' rows='5' cols='50' "
                + "maxlength='5000' required></textarea></p>");
        out.println("<button type='submit'>Publicar</button></form><hr><h2><strong>Todos los comentarios</strong></h2>");
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT f.id,f.creador_id,f.titulo,f.descripcion,f.fecha_creacion,u.nombre "
                + "FROM foros f JOIN usuarios u ON u.id=f.creador_id ORDER BY f.id DESC");
                ResultSet rs = ps.executeQuery()) {
            boolean hay = false;
            while (rs.next()) {
                hay = true;
                out.println("<article><h3 class='comment-heading'>Comentario de "
                        + html(nombreCorto(rs.getString("nombre"))) + "</h3><p>Fecha: "
                        + html(rs.getTimestamp("fecha_creacion"))
                        + "</p><p>" + multilinea(rs.getString("descripcion")) + "</p>");
                mensajes(out, ruta, sesion, c, rs.getInt("id"),
                        rs.getInt("creador_id") == (Integer) sesion.getAttribute("usuarioId"));
                out.println("</article><hr>");
            }
            if (!hay) out.println("<p>Todavía no hay comentarios en este Foro. Crea el primero.</p>");
        }
    }

    private void mensajes(PrintWriter out, String ruta, HttpSession sesion, Connection c,
            int foroId, boolean administrador) throws SQLException {
        out.println("<h4>Mensajes del foro</h4>");
        if (administrador) out.println("<p>Eres el administrador de este foro.</p>");
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT m.id,m.titulo,m.fecha_creacion,m.inapropiado,u.nombre,"
                + "CASE WHEN m.inapropiado=0 THEN m.contenido ELSE NULL END AS contenido "
                + "FROM mensajes m JOIN usuarios u ON u.id=m.autor_id WHERE m.foro_id=? ORDER BY m.id")) {
            ps.setInt(1, foroId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean hay = false;
                while (rs.next()) {
                    hay = true;
                    out.println("<section class='forum-message'><h4>" + html(rs.getString("titulo"))
                            + "</h4><p>Autor: " + html(rs.getString("nombre")) + " · Fecha: "
                            + html(rs.getTimestamp("fecha_creacion")) + "</p>");
                    if (rs.getBoolean("inapropiado")) {
                        out.println("<p class='aviso'>Mensaje marcado como inapropiado. Contenido oculto.</p>");
                    } else {
                        out.println("<p>" + multilinea(rs.getString("contenido")) + "</p>");
                        if (administrador) {
                            formulario(out, ruta, sesion, "marcarInapropiado");
                            oculto(out, "mensajeId", rs.getInt("id"));
                            out.println("<button type='submit'>Marcar como inapropiado</button></form>");
                        }
                    }
                    out.println("</section>");
                }
                if (!hay) out.println("<p>Todavía no hay mensajes en este foro.</p>");
            }
        }
        out.println("<details class='message-form'><summary>Publicar un mensaje en este foro</summary>");
        formulario(out, ruta, sesion, "publicarMensaje");
        oculto(out, "foroId", foroId);
        out.println("<label>Título<input name='titulo' maxlength='150' required></label>"
                + "<label>Mensaje<textarea name='contenido' rows='4' maxlength='5000' required></textarea></label>"
                + "<button type='submit'>Publicar mensaje</button></form></details>");
    }

    private int identificador(HttpServletRequest req, String nombre) {
        try {
            int id = Integer.parseInt(req.getParameter(nombre));
            if (id > 0) return id;
        } catch (NumberFormatException e) {
            // Los identificadores recibidos deben ser enteros positivos.
        }
        throw new IllegalArgumentException("Identificador inválido.");
    }

    private void preparar(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String nonce = Base64.getEncoder().encodeToString(bytes);
        req.setAttribute("nonce", nonce);
        resp.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce
                + "'; style-src 'nonce-" + nonce + "' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; "
                + "form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
    }

    private String token(HttpSession sesion) {
        String valor = (String) sesion.getAttribute("csrf");
        if (valor == null) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            valor = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            sesion.setAttribute("csrf", valor);
        }
        return valor;
    }

    private void formulario(PrintWriter out, String ruta, HttpSession sesion, String accion) {
        out.println("<form method='post' action='" + html(ruta) + "'>");
        oculto(out, "accion", accion);
        oculto(out, "csrf", token(sesion));
    }

    private void oculto(PrintWriter out, String nombre, Object valor) {
        out.println("<input type='hidden' name='" + html(nombre) + "' value='" + html(valor) + "'>");
    }

    private String texto(HttpServletRequest req, String nombre, int max) {
        String valor = req.getParameter(nombre);
        if (valor == null || valor.isBlank() || valor.trim().length() > max) {
            throw new IllegalArgumentException("El campo " + nombre + " es obligatorio y admite hasta " + max + " caracteres.");
        }
        return valor.trim();
    }

    private String nombreCorto(Object nombre) {
        if (nombre == null) return "";
        String[] palabras = nombre.toString().trim().split("\\s+");
        return palabras[0];
    }

    private String html(Object valor) {
        if (valor == null) return "";
        return valor.toString().replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String multilinea(String valor) {
        return html(valor).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
    }

}
