package jdos.hardware.mame;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javassist.ClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.CtNewConstructor;
import javassist.CtNewMethod;
import javassist.NotFoundException;
import jdos.Dosbox;


public class RasterizerCompiler extends RasterizerCompilerCommon {

    private static final Logger logger = System.getLogger(RasterizerCompiler.class.getName());

    static private class SaveInfo {

        public SaveInfo(raster_info info, byte[] byteCode) {
            this.info = info;
            this.byteCode = byteCode;
        }

        final raster_info info;
        final byte[] byteCode;
    }

    private static final List<SaveInfo> savedClasses = new ArrayList<>();

    static public void save(ZipOutputStream out) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(1); // version
        dos.writeInt(savedClasses.size());
        for (int i = 0; i < savedClasses.size(); i++) {
            SaveInfo info = savedClasses.get(i);
            String name = "Rasterizer" + i;
            out.putNextEntry(new ZipEntry(name + ".class"));
            out.write(info.byteCode);
            dos.writeUTF(name);
            dos.writeInt(info.info.eff_color_path);
            dos.writeInt(info.info.eff_alpha_mode);
            dos.writeInt(info.info.eff_fog_mode);
            dos.writeInt(info.info.eff_fbz_mode);
            dos.writeInt(info.info.eff_tex_mode_0);
            dos.writeInt(info.info.eff_tex_mode_1);
        }
        out.putNextEntry(new ZipEntry("jdos/Rasterizer.index"));
        dos.flush();
        out.write(bos.toByteArray());
    }

    static private void getRegR(StringBuilder method, String value) {
        method.append("(").append(value).append(" >> 16) & 0xFF");
    }

    static private void getRegG(StringBuilder method, String value) {
        method.append("(").append(value).append(" >> 8) & 0xFF");
    }

    static private void getRegB(StringBuilder method, String value) {
        method.append(value).append(" & 0xFF");
    }

    static private void getRegA(StringBuilder method, String value) {
        method.append("(").append(value).append(" >> 24) & 0xFF");
    }

    static private void CLAMP(StringBuilder method, String val, String min, String max) {
        method.append("if (").append(val).append(" < ").append(min).append(") {\n");
        method.append("    ").append(val).append(" = ").append(min).append(";\n");
        method.append("} else if (").append(val).append(" > ").append(max).append(") {\n");
        method.append("    ").append(val).append(" = ").append(max).append(";\n");
        method.append("}\n");
    }

    static private void CLAMPED_Z(StringBuilder method, String iterz, int fbzcp, String result) {
        method.append(result).append(" = ").append(iterz).append(" >> 12;\n");
        if (!VoodooCommon.FBZCP_RGBZW_CLAMP(fbzcp)) {
            method.append(result).append(" &= 0xfffff;\n");
            method.append("if (").append(result).append(" == 0xfffff)\n");
            method.append("    ").append(result).append(" = 0;\n");
            method.append("else if (").append(result).append(" == 0x10000)\n");
            method.append("    ").append(result).append(" = 0xffff;\n");
            method.append("else\n");
            method.append("    ").append(result).append(" &= 0xffff;\n");
        } else {
            CLAMP(method, result, "0", "0xffff");
        }
    }

    static private void CLAMPED_W(StringBuilder method, String iterw, int fbzcp, String result) {
        method.append(result).append(" = (short)").append(iterw).append(" >> 32;\n");
        if (!VoodooCommon.FBZCP_RGBZW_CLAMP(fbzcp)) {
            method.append(result).append(" &= 0xffff;\n");
            method.append("if (").append(result).append(" == 0xffff)\n");
            method.append("    ").append(result).append(" = 0;\n");
            method.append("else if (").append(result).append(" == 0x100)\n");
            method.append("    ").append(result).append(" = 0xff;\n");
            method.append(result).append(" &= 0xff;\n");
        } else {
            CLAMP(method, result, "0", "0xff");
        }
    }

    static private void TEXTURE_PIPELINE(StringBuilder method, int texMode, String lodBase, String iters, String itert, String iterw) {
//        int blendr, blendg, blendb, blenda;
//        int tr, tg, tb, ta;
//        int oow, s, t, lod, ilod;
//        int smax, tmax;
//        int texbase;
//        int c_local;

        /* determine the S/T/LOD values for this texture */
        if (VoodooCommon.TEXMODE_ENABLE_PERSPECTIVE(texMode)) {
            method.append("int lod, oow;\n");
            method.append("{\n");
            method.append("""
                        int temp, recip, rlog;
                        int interp;
                        int tablePos;
                        boolean neg = false;
                        int lz, exp = 0;
                    """);
            method.append("    if (").append(iterw).append(" < 0) {\n");
            method.append("        ").append(iterw).append(" = -").append(iterw).append("""
                    ;
                            neg = true;
                        }
                    """);

            method.append("    if ((").append(iterw).append(" & 0xffff00000000l)!=0) {\n");
            method.append("        temp = (int)(").append(iterw).append("""
                     >> 16);
                            exp -= 16;
                         } else {
                             temp = (int)\
                    """).append(iterw).append("""
                    ;
                         }
                    """);
            method.append("     if (temp == 0) {\n" +
                    "         lod = 1000 << ").append(VoodooCommon.LOG_OUTPUT_PREC).append("""
                    ;
                             oow = neg ? 0x80000000 : 0x7fffffff;
                         } else {
                    """);
            method.append("""
                             lz = Integer.numberOfLeadingZeros(temp);
                             temp <<= lz;
                             exp += lz;
                             tablePos = (temp >>> (31 - \
                    """).append(VoodooCommon.RECIPLOG_LOOKUP_BITS).append(" - 1)) & ((2 << ").append(VoodooCommon.RECIPLOG_LOOKUP_BITS).append(") - 2);\n" +
                    "         interp = (temp >>> (31 - ").append(VoodooCommon.RECIPLOG_LOOKUP_BITS).append("""
                     - 8)) & 0xff;
                             rlog = (VoodooCommon.voodoo_reciplog[tablePos+1] * (0x100 - interp) + VoodooCommon.voodoo_reciplog[tablePos+3] * interp) >>> 8;
                             recip = (VoodooCommon.voodoo_reciplog[tablePos] * (0x100 - interp) + VoodooCommon.voodoo_reciplog[tablePos+2] * interp) >>> 8;
                             rlog = (rlog + (1 << (\
                    """).append(VoodooCommon.RECIPLOG_LOOKUP_PREC).append(" - ").append(VoodooCommon.LOG_OUTPUT_PREC).append(" - 1))) >> (").append(VoodooCommon.RECIPLOG_LOOKUP_PREC).append(" - ").append(VoodooCommon.LOG_OUTPUT_PREC).append(");\n" +
                    "         lod = ((exp - (31 - ").append(VoodooCommon.RECIPLOG_INPUT_PREC).append(")) << ").append(VoodooCommon.LOG_OUTPUT_PREC).append(") - rlog;\n" +
                    "         exp += (").append(VoodooCommon.RECIP_OUTPUT_PREC).append(" - ").append(VoodooCommon.RECIPLOG_LOOKUP_PREC).append(") - (31 - ").append(VoodooCommon.RECIPLOG_INPUT_PREC).append("""
                    );
                             if (exp < 0)
                                 recip >>>= -exp;
                             else
                                 recip <<= exp;
                             oow = (neg && recip>0) ? -recip : recip;
                        }
                    }
                    """);
            method.append("int s = (int)(((long)oow * ").append(iters).append(") >>> 29);\n");
            method.append("int t = (int)(((long)oow * ").append(itert).append(") >>> 29);\n");
            method.append("lod += ").append(lodBase).append(";\n");
        } else {
            method.append("int s = (int)(").append(iters).append(" >> 14);\n");
            method.append("int t = (int)(").append(itert).append(" >> 14);\n");
            method.append("int lod = ").append(lodBase).append(";\n");
        }

        /* clamp W */
        if (VoodooCommon.TEXMODE_CLAMP_NEG_W(texMode))
            method.append("if (").append(iterw).append(" < 0) s = t = 0;\n");

        method.append("lod += tmu.lodbias;\n");
        if (VoodooCommon.TEXMODE_ENABLE_LOD_DITHER(texMode))
            method.append("lod += dither4[dither4Pos+(x & 3)] << 4;\n");
        method.append("""
                if (lod < tmu.lodmin)
                    lod = tmu.lodmin;
                if (lod > tmu.lodmax)
                    lod = tmu.lodmax;
                """);

        /* now the LOD is in range; if we don't own this LOD, take the next one */
        method.append("""
                int ilod = lod >> 8;
                if (((tmu.lodmask >> ilod) & 1)==0)
                    ilod++;
                """);

        method.append("""
                int texbase = tmu.lodoffset[ilod];
                int smax = tmu.wmask >> ilod;
                int tmax = tmu.hmask >> ilod;
                """);

        int pointSampled = 1;


        if (!VoodooCommon.TEXMODE_MAGNIFICATION_FILTER(texMode) && !VoodooCommon.TEXMODE_MINIFICATION_FILTER(texMode)) {
            pointSampled = 0;
        } else if (VoodooCommon.TEXMODE_MAGNIFICATION_FILTER(texMode) && VoodooCommon.TEXMODE_MINIFICATION_FILTER(texMode)) {
            pointSampled = 2;
        }
        method.append("int c_local;\n");
        if (pointSampled == 1) {
            if (!VoodooCommon.TEXMODE_MAGNIFICATION_FILTER(texMode))
                method.append("if (lod == tmu.lodmin) {\n");
            if (!VoodooCommon.TEXMODE_MINIFICATION_FILTER(texMode)) {
                method.append("if (lod != tmu.lodmin) {\n");
            }
        }
        if (pointSampled <= 1) {
            method.append("""
                    s >>= ilod + 18;
                    t >>= ilod + 18;
                    """);

            /* clamp/wrap S/T if necessary */
            if (VoodooCommon.TEXMODE_CLAMP_S(texMode))
                CLAMP(method, "s", "0", "smax");

            if (VoodooCommon.TEXMODE_CLAMP_T(texMode))
                CLAMP(method, "t", "0", "tmax");
            method.append("""
                    s &= smax;
                    t &= tmax;
                    t *= smax + 1;
                    """);

            /* fetch texel data */
            if (VoodooCommon.TEXMODE_FORMAT(texMode) < 8) {
                method.append("""
                        int texel0 = tmu.ram[(texbase + t + s) & tmu.mask] & 0xFF;
                        c_local = tmu.lookup[texel0];
                        """);
            } else {
                method.append("int texel0 = VoodooCommon.mem_readw(tmu.ram, (texbase + 2*(t + s)) & tmu.mask);\n");
                if (VoodooCommon.TEXMODE_FORMAT(texMode) >= 10 && VoodooCommon.TEXMODE_FORMAT(texMode) <= 12) {
                    method.append("c_local = tmu.lookup[texel0];\n");
                } else
                    method.append("c_local = (tmu.lookup[texel0 & 0xff] & 0xffffff) | ((texel0 & 0xff00) << 16);\n");
            }
        }
        if (pointSampled == 1)
            method.append("} else {\n");
        if (pointSampled >= 1) {
            method.append("""
                    s >>= ilod + 10;
                    t >>= ilod + 10;
                    s -= 0x80;
                    t -= 0x80;
                    int sfrac = s & tmu.bilinear_mask;
                    int tfrac = t & tmu.bilinear_mask;
                    s >>= 8;
                    t >>= 8;
                    int s1 = s + 1;
                    int t1 = t + 1;
                    """);

            /* clamp/wrap S/T if necessary */
            if (VoodooCommon.TEXMODE_CLAMP_S(texMode)) {
                CLAMP(method, "s", "0", "smax");
                CLAMP(method, "s1", "0", "smax");
            }
            if (VoodooCommon.TEXMODE_CLAMP_T(texMode)) {
                CLAMP(method, "t", "0", "tmax");
                CLAMP(method, "t1", "0", "tmax");
            }
            method.append("""
                    s &= smax;
                    s1 &= smax;
                    t &= tmax;
                    t1 &= tmax;
                    t *= smax + 1;
                    t1 *= smax + 1;
                    """);

            /* fetch texel data */
            if (VoodooCommon.TEXMODE_FORMAT(texMode) < 8) {
                method.append("""
                        int texel0 = tmu.ram[(texbase + t + s) & tmu.mask] & 0xFF;
                        int texel1 = tmu.ram[(texbase + t + s1) & tmu.mask] & 0xFF;
                        int texel2 = tmu.ram[(texbase + t1 + s) & tmu.mask] & 0xFF;
                        int texel3 = tmu.ram[(texbase + t1 + s1) & tmu.mask] & 0xFF;
                        texel0 = tmu.lookup[texel0];
                        texel1 = tmu.lookup[texel1];
                        texel2 = tmu.lookup[texel2];
                        texel3 = tmu.lookup[texel3];
                        """);
            } else {
                method.append("""
                        int texel0 = VoodooCommon.mem_readw(tmu.ram, (texbase + 2*(t + s)) & tmu.mask);
                        int texel1 = VoodooCommon.mem_readw(tmu.ram, (texbase + 2*(t + s1)) & tmu.mask);
                        int texel2 = VoodooCommon.mem_readw(tmu.ram, (texbase + 2*(t1 + s)) & tmu.mask);
                        int texel3 = VoodooCommon.mem_readw(tmu.ram, (texbase + 2*(t1 + s1)) & tmu.mask);
                        """);
                if (VoodooCommon.TEXMODE_FORMAT(texMode) >= 10 && VoodooCommon.TEXMODE_FORMAT(texMode) <= 12) {
                    method.append("""
                            texel0 = tmu.lookup[texel0];
                            texel1 = tmu.lookup[texel1];
                            texel2 = tmu.lookup[texel2];
                            texel3 = tmu.lookup[texel3];
                            """);
                } else {
                    method.append("""
                            texel0 = (tmu.lookup[texel0 & 0xff] & 0xffffff) | ((texel0 & 0xff00) << 16);
                            texel1 = (tmu.lookup[texel1 & 0xff] & 0xffffff) | ((texel1 & 0xff00) << 16);
                            texel2 = (tmu.lookup[texel2 & 0xff] & 0xffffff) | ((texel2 & 0xff00) << 16);
                            texel3 = (tmu.lookup[texel3 & 0xff] & 0xffffff) | ((texel3 & 0xff00) << 16);
                            """);
                }
            }
            method.append("c_local = VoodooCommon.rgba_bilinear_filter(texel0, texel1, texel2, texel3, sfrac, tfrac);\n");
        }
        if (pointSampled == 1)
            method.append("}\n");

        /* select zero/other for RGB */
        if (!VoodooCommon.TEXMODE_TC_ZERO_OTHER(texMode)) {
            method.append("int tr = ");
            getRegR(method, "texel");
            method.append(";\n");
            method.append("int tg = ");
            getRegG(method, "texel");
            method.append(";\n");
            method.append("int tb = ");
            getRegB(method, "texel");
            method.append(";\n");
        } else {
            method.append("int tr = 0, tg = 0, tb = 0;\n");
        }
        /* select zero/other for alpha */
        if (!VoodooCommon.TEXMODE_TCA_ZERO_OTHER(texMode)) {
            method.append("int ta = ");
            getRegA(method, "texel");
            method.append(";\n");
        } else {
            method.append("int ta = 0;\n");
        }

        /* potentially subtract c_local */
        if (VoodooCommon.TEXMODE_TC_SUB_CLOCAL(texMode)) {
            method.append("tr -= ");
            getRegR(method, "c_local");
            method.append(";\n");
            method.append("tg -= ");
            getRegG(method, "c_local");
            method.append(";\n");
            method.append("tb -= ");
            getRegB(method, "c_local");
            method.append(";\n");
        }
        if (VoodooCommon.TEXMODE_TCA_SUB_CLOCAL(texMode)) {
            method.append("ta -= ");
            getRegA(method, "c_local");
            method.append(";\n");
        }

        if (VoodooCommon.TEXMODE_TC_MSELECT(texMode) == 0 && VoodooCommon.TEXMODE_TCA_MSELECT(texMode) == 0 && !VoodooCommon.TEXMODE_TC_REVERSE_BLEND(texMode) && !VoodooCommon.TEXMODE_TCA_REVERSE_BLEND(texMode)) {
            logger.log(Level.DEBUG, "  removed textured blend");
        } else {
            /* blend RGB */
            switch (VoodooCommon.TEXMODE_TC_MSELECT(texMode)) {
                default:    /* reserved */
                case 0:     /* zero */
                    method.append("int blendr = 0, blendg = 0, blendb = 0;\n");
                    break;

                case 1:     /* c_local */
                    method.append("int blendr = ");
                    getRegR(method, "c_local");
                    method.append(";\n");
                    method.append("int blendg = ");
                    getRegG(method, "c_local");
                    method.append(";\n");
                    method.append("int blendb = ");
                    getRegB(method, "c_local");
                    method.append(";\n");
                    break;

                case 2:     /* a_other */
                    method.append("int blendr = ");
                    getRegA(method, "texel");
                    method.append(";\n");
                    method.append("int blendg = blendr;\n");
                    method.append("int blendb = blendr;\n");
                    break;

                case 3:     /* a_local */
                    method.append("int blendr = ");
                    getRegA(method, "c_local");
                    method.append(";\n");
                    method.append("int blendg = blendr;\n");
                    method.append("int blendb = blendr;\n");
                    break;

                case 4:     /* LOD (detail factor) */
                    method.append("int blendr, blendg, blendb;\n");
                    method.append("""
                            if (tmu.detailbias <= lod) {
                                blendr = blendg = blendb = 0;
                            } else {
                                blendr = (((tmu.detailbias - lod) << tmu.detailscale) >> 8);
                                    if (blendr > tmu.detailmax)
                                        blendr = tmu.detailmax;
                                blendg = blendb = blendr;
                            }
                            """);
                    break;

                case 5:     /* LOD fraction */
                    method.append("""
                            int blendr = lod & 0xff;
                            int blendg = blendr, blendb = blendr;
                            """);
                    break;
            }

            /* blend alpha */
            switch (VoodooCommon.TEXMODE_TCA_MSELECT(texMode)) {
                default:    /* reserved */
                case 0:     /* zero */
                    method.append("int blenda = 0;\n");
                    break;

                case 1:     /* c_local */
                    method.append("int blenda = ");
                    getRegA(method, "c_local");
                    method.append(";\n");
                    break;

                case 2:     /* a_other */
                    method.append("int blenda = ");
                    getRegA(method, "texel");
                    method.append(";\n");
                    break;

                case 3:     /* a_local */
                    method.append("int blenda = ");
                    getRegA(method, "c_local");
                    method.append(";\n");
                    break;

                case 4:     /* LOD (detail factor) */
                    method.append("""
                            int blenda;
                            if (tmu.detailbias <= lod) {
                                blenda = 0;
                            } else {
                                blenda = (((tmu.detailbias - lod) << tmu.detailscale) >> 8);
                                if (blenda > tmu.detailmax)
                                    blenda = tmu.detailmax;
                            }
                            """);
                    break;
                case 5:     /* LOD fraction */
                    method.append("int blenda = lod & 0xff;\n");
                    break;
            }

            /* reverse the RGB blend */
            if (!VoodooCommon.TEXMODE_TC_REVERSE_BLEND(texMode)) {
                method.append("""
                        blendr ^= 0xff;
                        blendg ^= 0xff;
                        blendb ^= 0xff;
                        """);
            }

            /* reverse the alpha blend */
            if (!VoodooCommon.TEXMODE_TCA_REVERSE_BLEND(texMode))
                method.append("blenda ^= 0xff;");

            /* do the blend */
            method.append("""
                    tr = (tr * (blendr + 1)) >> 8;
                    tg = (tg * (blendg + 1)) >> 8;
                    tb = (tb * (blendb + 1)) >> 8;
                    ta = (ta * (blenda + 1)) >> 8;
                    """);
        }
        /* add clocal or alocal to RGB */
        switch (VoodooCommon.TEXMODE_TC_ADD_ACLOCAL(texMode)) {
            case 3:     /* reserved */
            case 0:     /* nothing */
                break;

            case 1:     /* add c_local */
                method.append("tr += ");
                getRegR(method, "c_local");
                method.append(";\n");
                method.append("tg += ");
                getRegG(method, "c_local");
                method.append(";\n");
                method.append("tb += ");
                getRegB(method, "c_local");
                method.append(";\n");
                break;

            case 2:     /* add_alocal */
                method.append("tr += ");
                getRegR(method, "c_local");
                method.append(";\n");
                method.append("tg += ");
                getRegG(method, "c_local");
                method.append(";\n");
                method.append("tb += ");
                getRegB(method, "c_local");
                method.append(";\n");
                break;
        }

        /* add clocal or alocal to alpha */
        if (VoodooCommon.TEXMODE_TCA_ADD_ACLOCAL(texMode) != 0) {
            method.append("ta += ");
            getRegA(method, "c_local");
            method.append(";\n");
        }

        /* clamp */
        CLAMP(method, "tr", "0", "255");
        CLAMP(method, "tg", "0", "255");
        CLAMP(method, "tb", "0", "255");
        CLAMP(method, "ta", "0", "255");
        method.append("texel = tb | (tg << 8) | (tr << 16) | (ta << 24);\n");

        /* invert */
        if (VoodooCommon.TEXMODE_TC_INVERT_OUTPUT(texMode))
            method.append("texel ^= 0x00ffffff;\n");
        if (VoodooCommon.TEXMODE_TCA_INVERT_OUTPUT(texMode)) {
            method.append("texel = (texel & 0x00FFFFFF) | (((texel >>> 24) ^ 0xFF) & 0xFF);\n");
        }
    }

    static public void compile(raster_info info, int tmuCount, int colorPath, int alphaMode, int fogMode, int fbzMode, int textureMode0, int textureMode1) {
        if (!Dosbox.allPrivileges)
            return;
        StringBuilder method = new StringBuilder();
        compile(method, tmuCount, colorPath, alphaMode, fogMode, fbzMode, textureMode0, textureMode1);
        raster_info result = compileMethod(method, info);
        // make it live
        if (result != null) {
            info.callback = result.callback;
            logger.log(Level.DEBUG, "compiled " + count + " rasterizers");
            //logger.log(Level.DEBUG,method.toString());
        }

//        logger.log(Level.DEBUG,"static final public class Rast extends VoodooCommon.raster_info implements poly_draw_scanline_func {\n" +
//                "        public Rast() {\n" +
//                "            this.eff_color_path = "+info.eff_color_path+";\n" +
//                "            this.eff_alpha_mode = "+info.eff_alpha_mode+";\n" +
//                "            this.eff_fog_mode = "+info.eff_fog_mode+";\n" +
//                "            this.eff_fbz_mode = "+info.eff_fbz_mode+";\n" +
//                "            this.eff_tex_mode_0 = "+info.eff_tex_mode_0+";\n" +
//                "            this.eff_tex_mode_1 = "+info.eff_tex_mode_1+";\n" +
//                "            this.callback = this;\n" +
//                "        }\n" +
//                "\n" +
//                "        public void call(short[] dest, int destOffset, int y, poly_extent extent, poly_extra_data extra, int threadid) {");
//        logger.log(Level.DEBUG,method.toString());
//        logger.log(Level.DEBUG,"        }\n" +
//                "    }\n" +
//                "");
    }

    static private void compile(StringBuilder method, int tmuCount, int colorPath, int alphaMode, int fogMode, int fbzMode, int textureMode0, int textureMode1) {
        method.append("""
                final VoodooCommon v = extra.state;
                final stats_block stats = v.thread_stats[threadid];
                """);
        if (VoodooCommon.FBZMODE_ENABLE_DITHERING(fbzMode)) {
            method.append("""
                    byte[] dither_lookup = null;
                    int dither_lookupPos = 0;
                    byte[] dither4 = null;
                    int dither4Pos = 0;
                    byte[] dither = null;
                    int ditherPos=0;""");
        }
        boolean uses_depthPos = VoodooCommon.FBZMODE_AUX_BUFFER_MASK(fbzMode) || VoodooCommon.FBZMODE_ENABLE_DEPTHBUF(fbzMode);
        method.append("""
                int startx = extent.startx;
                int stopx = extent.stopx;
                int iterz;
                long iterw;
                long iterw0 = 0, iterw1 = 0;
                long iters0 = 0, iters1 = 0;
                long itert0 = 0, itert1 = 0;
                int destPos;
                int dx, dy;
                int scry=y;
                int x;
                """);
        if (uses_depthPos)
            method.append("int depthPos;\n");

        if (VoodooCommon.FBZMODE_Y_ORIGIN(fbzMode)) {
            method.append("scry = (v.fbi.yorigin - y) & 0x3ff;\n");
        }

        if (VoodooCommon.FBZMODE_ENABLE_DITHERING(fbzMode)) {
            method.append("""
                    dither4 = v.dither_matrix_4x4;
                    dither4Pos = (y & 3) * 4;
                    """);
            if (!VoodooCommon.FBZMODE_DITHER_TYPE(fbzMode)) {
                method.append("""
                        dither = dither4;
                        ditherPos = dither4Pos;
                        dither_lookup = v.dither4_lookup;
                        dither_lookupPos = (y & 3) << 11;
                        """);
            } else {
                method.append("""
                        dither = v.dither_matrix_2x2;
                        ditherPos = (y & 3) * 4;
                        dither_lookup = dither2_lookup;
                        dither_lookupPos = (y & 3) << 11;
                        """);
            }
        }

        if (VoodooCommon.FBZMODE_ENABLE_CLIPPING(fbzMode)) {
            method.append("if (scry < ((v.reg[").append(VoodooCommon.clipLowYHighY).append("] >> 16) & 0x3ff) || scry >= (v.reg[").append(VoodooCommon.clipLowYHighY).append("""
                    ] & 0x3ff)) {
                        stats.pixels_in += stopx - startx;
                        stats.clip_fail += stopx - startx;
                        return;
                    }
                    int tempclip = (v.reg[""").append(VoodooCommon.clipLeftRight).append("""
                    ] >> 16) & 0x3ff;
                    if (startx < tempclip) {
                        stats.pixels_in += tempclip - startx;
                        v.stats.total_clipped += tempclip - startx;
                        startx = tempclip;
                    }
                    tempclip = v.reg[""").append(VoodooCommon.clipLeftRight).append("""
                    ] & 0x3ff;
                    if (stopx >= tempclip) {
                        stats.pixels_in += stopx - tempclip;
                        v.stats.total_clipped += stopx - tempclip;
                        stopx = tempclip - 1;
                    }
                    """);
        }
        method.append("destPos = destOffset+scry * v.fbi.rowpixels;\n");
        if (uses_depthPos)
            method.append("depthPos = (v.fbi.auxoffs != -1) ? (v.fbi.auxoffs / 2 + scry * v.fbi.rowpixels) : -1;\n");
        method.append("""
                dx = startx - (extra.ax >> 4);
                dy = y - (extra.ay >> 4);
                int iterr = extra.startr + dy * extra.drdy + dx * extra.drdx;
                int iterg = extra.startg + dy * extra.dgdy + dx * extra.dgdx;
                int iterb = extra.startb + dy * extra.dbdy + dx * extra.dbdx;
                int itera = extra.starta + dy * extra.dady + dx * extra.dadx;
                iterz = extra.startz + dy * extra.dzdy + dx * extra.dzdx;
                iterw = extra.startw + dy * extra.dwdy + dx * extra.dwdx;
                """);
        if (tmuCount >= 1) {
            method.append("""
                    iterw0 = extra.startw0 + dy * extra.dw0dy + dx * extra.dw0dx;
                    iters0 = extra.starts0 + dy * extra.ds0dy + dx * extra.ds0dx;
                    itert0 = extra.startt0 + dy * extra.dt0dy + dx * extra.dt0dx;
                    """);
        }
        if (tmuCount >= 2) {
            method.append("""
                    iterw1 = extra.startw1 + dy * extra.dw1dy + dx * extra.dw1dx;
                    iters1 = extra.starts1 + dy * extra.ds1dy + dx * extra.ds1dx;
                    itert1 = extra.startt1 + dy * extra.dt1dy + dx * extra.dt1dx;
                    """);
        }


        method.append("for (x = startx; x < stopx; x++");
        method.append(", iterr += extra.drdx, iterg += extra.dgdx, iterb += extra.dbdx, itera += extra.dadx, iterz += extra.dzdx, iterw += extra.dwdx");
        if (tmuCount >= 1) {
            method.append(", iterw0 += extra.dw0dx, iters0 += extra.ds0dx, itert0 += extra.dt0dx");
        }
        if (tmuCount >= 2) {
            method.append(", iterw1 += extra.dw1dx, iters1 += extra.ds1dx, itert1 += extra.dt1dx");
        }
        method.append("""
                ){
                int texel = 0;
                int iterargb = 0;
                int r, g, b;
                """);
        boolean uses_a = (VoodooCommon.FBZMODE_AUX_BUFFER_MASK(fbzMode) && VoodooCommon.FBZMODE_ENABLE_ALPHA_PLANES(fbzMode)) ||
                VoodooCommon.ALPHAMODE_SRCRGBBLEND(alphaMode) == 1 || VoodooCommon.ALPHAMODE_SRCRGBBLEND(alphaMode) == 5 || VoodooCommon.ALPHAMODE_SRCRGBBLEND(alphaMode) == 15 ||
                VoodooCommon.ALPHAMODE_DSTRGBBLEND(alphaMode) == 1 || VoodooCommon.ALPHAMODE_DSTRGBBLEND(alphaMode) == 5;
        if (uses_a)
            method.append("int a;\n");
        method.append("stats.pixels_in++;\n");

        if (VoodooCommon.FBZMODE_ENABLE_STIPPLE(fbzMode)) {
            /* rotate mode */
            if (!VoodooCommon.FBZMODE_STIPPLE_PATTERN(fbzMode)) {
                method.append("v.reg[").append(VoodooCommon.stipple).append("] = (v.reg[").append(VoodooCommon.stipple).append("] << 1) | (v.reg[").append(VoodooCommon.stipple).append("] >> 31);\n");
                method.append("if ((v.reg[").append(VoodooCommon.stipple).append("""
                        ] & 0x80000000) == 0) {
                            v.stats.total_stippled++;
                            continue;
                        }
                        """);
            } else { /* pattern mode */
                method.append("if (((reg[").append(VoodooCommon.stipple).append("""
                        ] >> (((y & 3) << 3) | (~x & 7))) & 1) == 0) {
                            v.stats.total_stippled++;
                            continue;
                        }
                        """);
            }
        }

        boolean needDepthVal = (VoodooCommon.FBZMODE_ENABLE_DEPTHBUF(fbzMode) && !VoodooCommon.FBZMODE_DEPTH_SOURCE_COMPARE(fbzMode)) || (VoodooCommon.FBZMODE_AUX_BUFFER_MASK(fbzMode) && !VoodooCommon.FBZMODE_ENABLE_ALPHA_PLANES(fbzMode));
        boolean needWFloat = (needDepthVal && VoodooCommon.FBZMODE_WBUFFER_SELECT(fbzMode) && !VoodooCommon.FBZMODE_DEPTH_FLOAT_SELECT(fbzMode)) || (VoodooCommon.FOGMODE_ENABLE_FOG(fogMode) && VoodooCommon.FOGMODE_FOG_ZALPHA(fogMode) == 0);

        if (needWFloat) {
            method.append("""
                    int wfloat;
                    if ((iterw & 0xffff00000000l)!=0) {
                        wfloat = 0x0000;
                    } else {
                        int temp = (int)iterw;
                        if ((temp & 0xffff0000) == 0) {
                            wfloat = 0xffff;
                        } else {
                            int exp = Integer.numberOfLeadingZeros(temp);
                            wfloat = ((exp << 12) | ((~temp >> (19 - exp)) & 0xfff)) + 1;
                        }
                    }
                    """);
        }
        if (needDepthVal) {
            if (!VoodooCommon.FBZMODE_WBUFFER_SELECT(fbzMode)) {
                method.append("int ");
                CLAMPED_Z(method, "iterz", colorPath, "depthval");
            } else if (!VoodooCommon.FBZMODE_DEPTH_FLOAT_SELECT(fbzMode))
                method.append("int depthval = wfloat;\n");
            else {
                method.append("int depthval;\n");
                method.append("""
                        if ((iterz & 0xf0000000)!=0) {
                            depthval = 0x0000;
                        } else {
                            int temp = iterz << 4;
                            if ((temp & 0xffff0000) == 0) {
                                depthval = 0xffff;
                            } else {
                                int exp = Integer.numberOfLeadingZeros(temp);
                                depthval = ((exp << 12) | ((~temp >> (19 - exp)) & 0xfff)) + 1;
                            }
                        }
                        """);
            }
        }

        if (VoodooCommon.FBZMODE_ENABLE_DEPTH_BIAS(fbzMode)) {
            method.append("depthval += (short)v.reg[").append(VoodooCommon.zaColor).append("];\n");
            CLAMP(method, "depthval", "0", "0xffff");
        }

        if (VoodooCommon.FBZMODE_ENABLE_DEPTHBUF(fbzMode)) {
            if (!VoodooCommon.FBZMODE_DEPTH_SOURCE_COMPARE(fbzMode))
                method.append("int depthsource = depthval;\n");
            else
                method.append("int depthsource = v.reg[").append(VoodooCommon.zaColor).append("] & 0xFFFF;\n");

            /* test against the depth buffer */
            switch (VoodooCommon.FBZMODE_DEPTH_FUNCTION(fbzMode)) {
                case 0:     /* depthOP = never */
                    method.append("""
                            stats.zfunc_fail++;
                            continue;
                            """);
                    break;
                case 1:     /* depthOP = less than */
                    method.append("""
                            if (depthsource >= (v.fbi.ram[depthPos+x] & 0xFFFF)) {
                                stats.zfunc_fail++;
                                continue;
                            }
                            """);
                    break;
                case 2:     /* depthOP = equal */
                    method.append("""
                            if (depthsource != (v.fbi.ram[depthPos+x] & 0xFFFF)) {
                                stats.zfunc_fail++;
                                continue;
                            }
                            """);
                    break;
                case 3:     /* depthOP = less than or equal */
                    method.append("""
                            if (depthsource > (v.fbi.ram[depthPos+x] & 0xFFFF)) {
                                stats.zfunc_fail++;
                                continue;
                            }
                            """);
                    break;
                case 4:     /* depthOP = greater than */
                    method.append("""
                            if (depthsource <= (v.fbi.ram[depthPos+x] & 0xFFFF)) {
                                stats.zfunc_fail++;
                                continue;
                            }
                            """);
                    break;
                case 5:     /* depthOP = not equal */
                    method.append("""
                            if (depthsource == (v.fbi.ram[depthPos+x] & 0xFFFF)) {
                                stats.zfunc_fail++;
                                continue;
                            }
                            """);
                    break;
                case 6:     /* depthOP = greater than or equal */
                    method.append("""
                            if (depthsource < (v.fbi.ram[depthPos+x] & 0xFFFF)) {
                                stats.zfunc_fail++;
                                continue;
                            }
                            """);
                    break;
                case 7:     /* depthOP = always */
                    break;
            }
        }

        if (tmuCount >= 2) {
            method.append("if (v.tmu[1].lodmin < (8 << 8)) {\n");
            method.append("  tmu_state tmu = v.tmu[1];\n");
            TEXTURE_PIPELINE(method, textureMode1, "extra.lodbase1", "iters1", "itert1", "iterw1");
            method.append("}\n");
        }

        if (tmuCount >= 1) {
            method.append("if (v.tmu[0].lodmin < (8 << 8)) {\n");
            method.append("    if (((v.reg[v.tmu[0].reg+VoodooCommon.trexInit1] >> 18) & 1)==0){");
            method.append("        tmu_state tmu = v.tmu[0];\n");
            TEXTURE_PIPELINE(method, textureMode0, "extra.lodbase0", "iters0", "itert0", "iterw0");
            method.append("""
                        } else {
                            texel = 64;
                        }
                    }
                    """);

        }

        boolean uses_iterargb = VoodooCommon.FBZCP_CC_RGBSELECT(colorPath) == 0 ||
                VoodooCommon.FBZCP_CC_ASELECT(colorPath) == 0 ||
                VoodooCommon.FBZCP_CCA_LOCALSELECT(colorPath) == 0 ||
                VoodooCommon.FBZCP_CC_LOCALSELECT_OVERRIDE(colorPath) || (!VoodooCommon.FBZCP_CC_LOCALSELECT_OVERRIDE(colorPath) && !VoodooCommon.FBZCP_CC_LOCALSELECT(colorPath)) ||
                (VoodooCommon.FOGMODE_ENABLE_FOG(fogMode) && !VoodooCommon.FOGMODE_FOG_CONSTANT(fogMode) && VoodooCommon.FOGMODE_FOG_ZALPHA(fogMode) == 1);
        if (uses_iterargb) {
            method.append("""
                    int ir, ig, ib, ia;
                    int cr = iterr >> 12;
                    int cg = iterg >> 12;
                    int cb = iterb >> 12;
                    int ca = itera >> 12;
                    """);

            if (!VoodooCommon.FBZCP_RGBZW_CLAMP(colorPath)) {
                method.append("""
                        cr &= 0xfff;
                        ir = cr & 0xFF;
                        if (cr == 0xfff) ir = 0;
                        else if (cr == 0x100) ir = 0xff;
                        cg &= 0xfff;
                        ig = cg & 0xFF;
                        if (cg == 0xfff) ig = 0;
                        else if (cg == 0x100) ig = 0xff;
                        cb &= 0xfff;
                        ib = cb & 0xFF;
                        if (cb == 0xfff) ib = 0;
                        else if (cb == 0x100) ib = 0xff;
                        ca &= 0xfff;
                        ia = ca & 0xFF;
                        if (ca == 0xfff) ia = 0;
                        else if (ca == 0x100) ia = 0xff;
                        """);
            } else {
                method.append("""
                        ir = (cr < 0) ? 0 : (cr > 0xff) ? 0xff : cr;
                        ig = (cg < 0) ? 0 : (cg > 0xff) ? 0xff : cg;
                        ib = (cb < 0) ? 0 : (cb > 0xff) ? 0xff : cb;
                        ia = (ca < 0) ? 0 : (ca > 0xff) ? 0xff : ca;
                        """);
            }
            method.append("iterargb = ib | (ig << 8) | (ir << 16) | (ia << 24);\n");
        }
        switch (VoodooCommon.FBZCP_CC_RGBSELECT(colorPath)) {
            case 0:     /* iterated RGB */
                method.append("int c_other = iterargb;\n");
                break;
            case 1:     /* texture RGB */
                method.append("int c_other = texel;\n");
                break;
            case 2:     /* color1 RGB */
                method.append("int c_other = v.reg[VoodooCommon.color1];\n");
                break;
            default:    /* reserved */
                method.append("int c_other = 0;\n");
                break;
        }

        if (VoodooCommon.FBZMODE_ENABLE_CHROMAKEY(fbzMode)) {
            method.append("if (!v.APPLY_CHROMAKEY(stats, ");
            method.append(fbzMode);
            method.append(", c_other)) continue;\n");
        }

        /* compute a_other */
        switch (VoodooCommon.FBZCP_CC_ASELECT(colorPath)) {
            case 0:     /* iterated alpha */
                method.append("c_other = (c_other & 0x00FFFFFF) | (iterargb & 0xFF000000);\n");
                break;

            case 1:     /* texture alpha */
                method.append("c_other = (c_other & 0x00FFFFFF) | (texel & 0xFF000000);\n");
                break;

            case 2:     /* color1 alpha */
                method.append("c_other = (c_other & 0x00FFFFFF) | (v.reg[VoodooCommon.color1] & 0xFF000000);\n");
                break;

            default:    /* reserved */
                method.append("c_other = (c_other & 0x00FFFFFF);\n");
                break;
        }
        if (VoodooCommon.FBZMODE_ENABLE_ALPHA_MASK(fbzMode) || VoodooCommon.ALPHAMODE_ALPHATEST(alphaMode)) {
            method.append("int c_other_a = ");
            getRegA(method, "c_other");
            method.append(";\n");
        }
        if (VoodooCommon.FBZMODE_ENABLE_ALPHA_MASK(fbzMode)) {
            method.append("""
                    if ((c_other_a & 1) == 0) {
                        stats.afunc_fail++;
                        continue;
                    }
                    """);
        }
        if (VoodooCommon.ALPHAMODE_ALPHATEST(alphaMode)) {
            method.append("if (!v.APPLY_ALPHATEST(stats, ").append(alphaMode).append(", c_other_a)) continue;\n");
        }
        boolean uses_c_local = VoodooCommon.FBZCP_CC_SUB_CLOCAL(colorPath) || VoodooCommon.FBZCP_CCA_SUB_CLOCAL(colorPath) ||
                VoodooCommon.FBZCP_CC_MSELECT(colorPath) == 1 || VoodooCommon.FBZCP_CC_MSELECT(colorPath) == 3 ||
                VoodooCommon.FBZCP_CCA_MSELECT(colorPath) == 1 || VoodooCommon.FBZCP_CCA_MSELECT(colorPath) == 3 ||
                VoodooCommon.FBZCP_CC_ADD_ACLOCAL(colorPath) == 1 || VoodooCommon.FBZCP_CC_ADD_ACLOCAL(colorPath) == 2 ||
                VoodooCommon.FBZCP_CCA_ADD_ACLOCAL(colorPath) != 0;

        if (uses_c_local) {
            if (!VoodooCommon.FBZCP_CC_LOCALSELECT_OVERRIDE(colorPath)) {
                if (!VoodooCommon.FBZCP_CC_LOCALSELECT(colorPath))
                    method.append("int c_local = iterargb;\n");
                else
                    method.append("int c_local = v.reg[VoodooCommon.color0];\n");
            } else {
                method.append("""
                        int c_local;
                        if ((texel & 0x80000000)==0)
                            c_local = iterargb;
                        else
                            c_local = v.reg[VoodooCommon.color0];
                        """);
            }

            /* compute a_local */
            switch (VoodooCommon.FBZCP_CCA_LOCALSELECT(colorPath)) {
                default:
                case 0:     /* iterated alpha */
                    method.append("c_local = (c_local & 0x00FFFFFF) | (iterargb & 0xFF000000);\n");
                    break;

                case 1:     /* color0 alpha */
                    method.append("c_local = (c_local & 0x00FFFFFF) | (v.reg[VoodooCommon.color0] & 0xFF000000);\n");
                    break;

                case 2:     /* clamped iterated Z[27:20] */ {
                    method.append("int temp = ");
                    CLAMPED_Z(method, "iterz", colorPath, "temp");
                    method.append("c_local = (c_local & 0x00FFFFFF) | (temp << 24);\n");
                    break;
                }

                case 3:     /* clamped iterated W[39:32] */ {
                    method.append("int temp = ");
                    CLAMPED_W(method, "iterw", colorPath, "temp");
                    method.append("c_local = (c_local & 0x00FFFFFF) | (temp << 24);\n");
                    break;
                }
            }
        }
        /* select zero or c_other */
        if (!VoodooCommon.FBZCP_CC_ZERO_OTHER(colorPath)) {
            method.append("r = ");
            getRegR(method, "c_other");
            method.append(";\n");
            method.append("g = ");
            getRegG(method, "c_other");
            method.append(";\n");
            method.append("b = ");
            getRegB(method, "c_other");
            method.append(";\n");
        } else {
            method.append("r = g = b = 0;\n");
        }

        if (uses_a) {
            if (!VoodooCommon.FBZCP_CCA_ZERO_OTHER(colorPath)) {
                method.append("a = ");
                getRegA(method, "c_other");
                method.append(";\n");
            } else {
                method.append("a = 0;\n");
            }
        }

        /* subtract c_local */
        if (VoodooCommon.FBZCP_CC_SUB_CLOCAL(colorPath)) {
            method.append("r -= ");
            getRegR(method, "c_local");
            method.append(";\n");
            method.append("g -= ");
            getRegG(method, "c_local");
            method.append(";\n");
            method.append("b -= ");
            getRegB(method, "c_local");
            method.append(";\n");
        }

        if (uses_a) {
            if (VoodooCommon.FBZCP_CCA_SUB_CLOCAL(colorPath)) {
                method.append("a -= ");
                getRegA(method, "c_local");
                method.append(";\n");
            }
        }
        if (VoodooCommon.FBZCP_CC_MSELECT(colorPath) == 0 && VoodooCommon.FBZCP_CCA_MSELECT(colorPath) == 0 && !VoodooCommon.FBZCP_CC_REVERSE_BLEND(colorPath) && (!VoodooCommon.FBZCP_CCA_REVERSE_BLEND(colorPath) || !uses_a)) {
            logger.log(Level.DEBUG, "  removed color path blend");
        } else {
            /* blend RGB */
            switch (VoodooCommon.FBZCP_CC_MSELECT(colorPath)) {
                default:    /* reserved */
                case 0:     /* 0 */
                    method.append("int blendr = 0, blendg = 0, blendb = 0;\n");
                    break;

                case 1:     /* c_local */
                    method.append("int blendr = ");
                    getRegR(method, "c_local");
                    method.append(";\n");
                    method.append("int blendg = ");
                    getRegG(method, "c_local");
                    method.append(";\n");
                    method.append("int blendb = ");
                    getRegB(method, "c_local");
                    method.append(";\n");
                    break;

                case 2:     /* a_other */
                    method.append("int blendr = ");
                    getRegA(method, "c_other");
                    method.append("""
                            ;
                            int blendb = blendr, blendg = blendr;
                            """);
                    break;

                case 3:     /* a_local */
                    method.append("int blendr = ");
                    getRegA(method, "c_local");
                    method.append("""
                            ;
                            int blendb = blendr, blendg = blendr;
                            """);
                    break;

                case 4:     /* texture alpha */
                    method.append("int blendr = ");
                    getRegA(method, "texel");
                    method.append("""
                            ;
                            int blendb = blendr, blendg = blendr;
                            """);
                    break;

                case 5:     /* texture RGB (Voodoo 2 only) */
                    method.append("int blendr = ");
                    getRegR(method, "texel");
                    method.append(";\n");
                    method.append("int blendg = ");
                    getRegG(method, "texel");
                    method.append(";\n");
                    method.append("int blendb = ");
                    getRegB(method, "texel");
                    method.append(";\n");
                    break;
            }

            if (uses_a) {
                switch (VoodooCommon.FBZCP_CCA_MSELECT(colorPath)) {
                    default:    /* reserved */
                    case 0:     /* 0 */
                        method.append("int blenda = 0;\n");
                        break;

                    case 1:     /* a_local */
                        method.append("int blenda = ");
                        getRegA(method, "c_local");
                        method.append(";\n");
                        break;

                    case 2:     /* a_other */
                        method.append("int blenda = ");
                        getRegA(method, "c_other");
                        method.append(";\n");
                        break;

                    case 3:     /* a_local */
                        method.append("int blenda = ");
                        getRegA(method, "c_local");
                        method.append(";\n");
                        break;

                    case 4:     /* texture alpha */
                        method.append("int blenda = ");
                        getRegA(method, "texel");
                        method.append(";\n");
                        break;
                }
            }
            /* reverse the RGB blend */
            if (!VoodooCommon.FBZCP_CC_REVERSE_BLEND(colorPath)) {
                method.append("""
                        blendr ^= 0xff;
                        blendg ^= 0xff;
                        blendb ^= 0xff;
                        """);
            }

            if (uses_a) {
                if (!VoodooCommon.FBZCP_CCA_REVERSE_BLEND(colorPath)) {
                    method.append("blenda ^= 0xff;\n");
                }
            }

            /* do the blend */
            method.append("""
                    r = (r * (blendr + 1)) >> 8;
                    g = (g * (blendg + 1)) >> 8;
                    b = (b * (blendb + 1)) >> 8;
                    """);
            if (uses_a)
                method.append("a = (a * (blenda + 1)) >> 8;\n");
        }

        /* add clocal or alocal to RGB */
        switch (VoodooCommon.FBZCP_CC_ADD_ACLOCAL(colorPath)) {
            case 3:     /* reserved */
            case 0:     /* nothing */
                break;

            case 1:     /* add c_local */
                method.append("r += ");
                getRegR(method, "c_local");
                method.append(";\n");
                method.append("g += ");
                getRegG(method, "c_local");
                method.append(";\n");
                method.append("b += ");
                getRegB(method, "c_local");
                method.append(";\n");
                break;

            case 2:     /* add_alocal */
                method.append("r += ");
                getRegA(method, "c_local");
                method.append(";\n");
                method.append("g += ");
                getRegA(method, "c_local");
                method.append(";\n");
                method.append("b += ");
                getRegA(method, "c_local");
                method.append(";\n");
                break;
        }

        if (uses_a) {
            if (VoodooCommon.FBZCP_CCA_ADD_ACLOCAL(colorPath) != 0) {
                method.append("a += ");
                getRegA(method, "c_local");
                method.append(";\n");
            }
        }

        /* clamp */
        CLAMP(method, "r", "0x00", "0xff");
        CLAMP(method, "g", "0x00", "0xff");
        CLAMP(method, "b", "0x00", "0xff");
        if (uses_a) {
            CLAMP(method, "a", "0x00", "0xff");
        }

        /* invert */
        if (VoodooCommon.FBZCP_CC_INVERT_OUTPUT(colorPath)) {
            method.append("""
                    r ^= 0xff;
                    g ^= 0xff;
                    b ^= 0xff;
                    """);
        }
        if (uses_a) {
            if (VoodooCommon.FBZCP_CCA_INVERT_OUTPUT(colorPath)) {
                method.append("a ^= 0xff;\n");
            }
        }
        if (VoodooCommon.ALPHAMODE_DSTRGBBLEND(alphaMode) == 15) {
            method.append("""
                    int prefogr = r;
                    int prefogg = g;
                    int prefogb = b;
                    """);
        }

        // APPLY_FOGGING(VV, FOGMODE, FBZCOLORPATH, XX, DITHER4, r, g, b, ITERZ, ITERW, ITERAXXX);
        if (VoodooCommon.FOGMODE_ENABLE_FOG(fogMode)) {
            method.append("int fogcolor = v.reg[VoodooCommon.fogColor];\n");

            /* constant fog bypasses everything else */
            if (VoodooCommon.FOGMODE_FOG_CONSTANT(fogMode)) {
                method.append("int fr = ");
                getRegR(method, "fogcolor");
                method.append(";\n");
                method.append("int fg = ");
                getRegG(method, "fogcolor");
                method.append(";\n");
                method.append("int fb = ");
                getRegB(method, "fogcolor");
                method.append(";\n");
            }
            /* non-constant fog comes from several sources */
            else {
                method.append("int fogblend = 0;\n");

                /* if fog_add is zero, we start with the fog color */
                if (!VoodooCommon.FOGMODE_FOG_ADD(fogMode)) {
                    method.append("int fr = ");
                    getRegR(method, "fogcolor");
                    method.append(";\n");
                    method.append("int fg = ");
                    getRegG(method, "fogcolor");
                    method.append(";\n");
                    method.append("int fb = ");
                    getRegB(method, "fogcolor");
                    method.append(";\n");
                } else {
                    method.append("int fr = 0, fg = 0, fb = 0;\n");
                }

                /* if fog_mult is zero, we subtract the incoming color */
                if (!VoodooCommon.FOGMODE_FOG_MULT(fogMode)) {
                    method.append("""
                            fr -= r;
                            fg -= g;
                            fb -= b;
                            """);
                }

                /* fog blending mode */
                switch (VoodooCommon.FOGMODE_FOG_ZALPHA(fogMode)) {
                    case 0:     /* fog table */ {
                        method.append("""
                                int delta = v.fbi.fogdelta[wfloat >> 10];
                                int deltaval = (delta & v.fbi.fogdelta_mask) * ((wfloat >> 2) & 0xff);
                                """);
                        if (VoodooCommon.FOGMODE_FOG_ZONES(fogMode)) {
                            method.append("""
                                    if (delta & 2) != 0)
                                        deltaval = -deltaval;
                                    """);
                        }
                        method.append("deltaval >>= 6;\n");
                        if (VoodooCommon.FOGMODE_FOG_DITHER(fogMode))
                            method.append("deltaval += dither4[dither4Pos + (x & 3)];\n");
                        method.append("""
                                deltaval >>= 4;
                                fogblend = v.fbi.fogblend[wfloat >> 10] + deltaval;
                                """);
                        break;
                    }
                    case 1:     /* iterated A */
                        method.append("fogblend = iterargb >>> 24;\n");
                        break;
                    case 2:     /* iterated Z */
                        CLAMPED_Z(method, "iterz", colorPath, "fogblend");
                        method.append("fogblend >>= 8;\n");
                        break;
                    case 3:     /* iterated W - Voodoo 2 only */
                        CLAMPED_W(method, "iterw", colorPath, "fogblend");
                        break;
                }

                /* perform the blend */
                method.append("""
                        fogblend++;
                        fr = (fr * fogblend) >> 8;
                        fg = (fg * fogblend) >> 8;
                        fb = (fb * fogblend) >> 8;
                        """);
            }

            /* if fog_mult is 0, we add this to the original color */
            if (!VoodooCommon.FOGMODE_FOG_MULT(fogMode)) {
                method.append("""
                        r += fr;
                        g += fg;
                        b += fb;
                        """);
            } else {
                method.append("""
                        r = fr;
                        g = fg;
                        b = fb;
                        """);
            }

            /* clamp */
            CLAMP(method, "r", "0", "0xff");
            CLAMP(method, "g", "0", "0xff");
            CLAMP(method, "b", "0", "0xff");
        }

        /* perform alpha blending */
        if (VoodooCommon.ALPHAMODE_ALPHABLEND(alphaMode)) {
            method.append("""
                    int dpix = dest[destPos+x] & 0xFFFF;
                    int dr = (dpix >> 8) & 0xf8;
                    int dg = (dpix >> 3) & 0xfc;
                    int db = (dpix << 3) & 0xf8;
                    int da =\s""").append(VoodooCommon.FBZMODE_ENABLE_ALPHA_PLANES(fbzMode) ? "dest[destPos+x] & 0xFFFF\n" : """
                    0xff;
                    int sr = r;
                    int sb = b;
                    int sg = g;
                    """);
            if (uses_a)
                method.append("int sa = a;\n");

            /* apply dither subtraction */
            if (VoodooCommon.FBZMODE_ALPHA_DITHER_SUBTRACT(fbzMode)) {
                method.append("""
                        int dith = dither[ditherPos+(x & 3)];
                        dr = ((dr << 1) + 15 - dith) >> 1;
                        dg = ((dg << 2) + 15 - dith) >> 2;
                        db = ((db << 1) + 15 - dith) >> 1;
                        """);
            }

            /* compute source portion */
            switch (VoodooCommon.ALPHAMODE_SRCRGBBLEND(alphaMode)) {
                default:    /* reserved */
                case 0:     /* AZERO */
                    method.append("r = g = b = 0;\n");
                    break;
                case 1:     /* ASRC_ALPHA */
                    method.append("""
                            r = (sr * (sa + 1)) >> 8;
                            g = (sg * (sa + 1)) >> 8;
                            b = (sb * (sa + 1)) >> 8;
                            """);
                    break;
                case 2:     /* A_COLOR */
                    method.append("""
                            r = (sr * (dr + 1)) >> 8;
                            g = (sg * (dg + 1)) >> 8;
                            b = (sb * (db + 1)) >> 8;
                            """);
                    break;
                case 3:     /* ADST_ALPHA */
                    method.append("""
                            r = (sr * (da + 1)) >> 8;
                            g = (sg * (da + 1)) >> 8;
                            b = (sb * (da + 1)) >> 8;
                            """);
                    break;
                case 4:     /* AONE */
                    break;
                case 5:     /* AOMSRC_ALPHA */
                    method.append("""
                            r = (sr * (0x100 - sa)) >> 8;
                            g = (sg * (0x100 - sa)) >> 8;
                            b = (sb * (0x100 - sa)) >> 8;
                            """);
                    break;
                case 6:     /* AOM_COLOR */
                    method.append("""
                            r = (sr * (0x100 - dr)) >> 8;
                            g = (sg * (0x100 - dg)) >> 8;
                            b = (sb * (0x100 - db)) >> 8;
                            """);
                    break;
                case 7:     /* AOMDST_ALPHA */
                    method.append("""
                            r = (sr * (0x100 - da)) >> 8;
                            g = (sg * (0x100 - da)) >> 8;
                            b = (sb * (0x100 - da)) >> 8;
                            """);
                    break;
                case 15:    /* ASATURATE */
                    method.append("""
                            int ta = (sa < (0x100 - da)) ? sa : (0x100 - da);
                            r = (sr * (ta + 1)) >> 8;
                            g = (sg * (ta + 1)) >> 8;
                            b = (sb * (ta + 1)) >> 8;
                            """);
                    break;
            }

            /* add in dest portion */
            switch (VoodooCommon.ALPHAMODE_DSTRGBBLEND(alphaMode)) {
                default:    /* reserved */
                case 0:     /* AZERO */
                    break;
                case 1:     /* ASRC_ALPHA */
                    method.append("""
                            r += (dr * (sa + 1)) >> 8;
                            g += (dg * (sa + 1)) >> 8;
                            b += (db * (sa + 1)) >> 8;
                            """);
                    break;
                case 2:     /* A_COLOR */
                    method.append("""
                            r += (dr * (sr + 1)) >> 8;
                            g += (dg * (sg + 1)) >> 8;
                            b += (db * (sb + 1)) >> 8;
                            """);
                    break;
                case 3:     /* ADST_ALPHA */
                    method.append("""
                            r += (dr * (da + 1)) >> 8;
                            g += (dg * (da + 1)) >> 8;
                            b += (db * (da + 1)) >> 8;
                            """);
                    break;
                case 4:     /* AONE */
                    method.append("""
                            r += dr;
                            g += dg;
                            b += db;
                            """);
                    break;
                case 5:     /* AOMSRC_ALPHA */
                    method.append("""
                            r += (dr * (0x100 - sa)) >> 8;
                            g += (dg * (0x100 - sa)) >> 8;
                            b += (db * (0x100 - sa)) >> 8;
                            """);
                    break;
                case 6:     /* AOM_COLOR */
                    method.append("""
                            r += (dr * (0x100 - sr)) >> 8;
                            g += (dg * (0x100 - sg)) >> 8;
                            b += (db * (0x100 - sb)) >> 8;
                            """);
                    break;
                case 7:     /* AOMDST_ALPHA */
                    method.append("""
                            r += (dr * (0x100 - da)) >> 8;
                            g += (dg * (0x100 - da)) >> 8;
                            b += (db * (0x100 - da)) >> 8;
                            """);
                    break;
                case 15:    /* A_COLORBEFOREFOG */
                    method.append("""
                            r += (dr * (prefogr + 1)) >> 8;
                            g += (dg * (prefogg + 1)) >> 8;
                            b += (db * (prefogb + 1)) >> 8;
                            """);
                    break;
            }
            if (uses_a) {
                if (VoodooCommon.ALPHAMODE_SRCALPHABLEND(alphaMode) == 4)
                    method.append("a = sa;\n");
                else
                    method.append("a = 0;\n");
                if (VoodooCommon.ALPHAMODE_DSTALPHABLEND(alphaMode) == 4)
                    method.append("a += da;\n");
            }
            /* clamp */
            CLAMP(method, "r", "0", "0xff");
            CLAMP(method, "g", "0", "0xff");
            CLAMP(method, "b", "0", "0xff");
            if (uses_a) {
                CLAMP(method, "a", "0", "0xff");
            }
        }

        /* write to framebuffer */
        if (VoodooCommon.FBZMODE_RGB_BUFFER_MASK(fbzMode)) {
            if (VoodooCommon.FBZMODE_ENABLE_DITHERING(fbzMode)) {
                method.append("""
                        int dithPos = dither_lookupPos + ((x & 3) << 1);
                        r = dither_lookup[dithPos+(r << 3) + 0];
                        g = dither_lookup[dithPos+(g << 3) + 1];
                        b = dither_lookup[dithPos+(b << 3) + 0];
                        """);
            } else {
                method.append("""
                        r >>>= 3;
                        g >>>= 2;
                        b >>>= 3;
                        """);
            }
            method.append("dest[destPos+x] = (short)((r << 11) | (g << 5) | b);\n");
        }

        /* write to aux buffer */
        if (VoodooCommon.FBZMODE_AUX_BUFFER_MASK(fbzMode)) {
            method.append("if (depthPos!=-1)\n");
            if (!VoodooCommon.FBZMODE_ENABLE_ALPHA_PLANES(fbzMode))
                method.append("v.fbi.ram[depthPos+x] = (short)depthval;\n");
            else
                method.append("v.fbi.ram[depthPos+x] = (short)a;\n");
        }
        method.append("stats.pixels_out++;\n");

        // close loop
        method.append("}\n");
    }

    static private final ClassPool pool = new ClassPool(true);

    static {
        pool.importPackage("jdos.hardware.mame.VoodooCommon");
        pool.importPackage("jdos.hardware.mame.Poly");
        pool.importPackage("jdos.hardware.mame.poly_extent");
        pool.importPackage("jdos.hardware.mame.poly_extra_data");
        pool.importPackage("jdos.hardware.mame.stats_block");
        pool.importPackage("jdos.hardware.mame.tmu_state");
        pool.insertClassPath(new ClassPath() {
            @Override
            public InputStream openClassfile(String s) throws NotFoundException {
                if (s.startsWith("jdos.")) {
                    s = "/" + s.replace('.', '/') + ".class";
                    return Dosbox.class.getResourceAsStream(s.substring(6));
                }
                return null;
            }

            @Override
            public URL find(String s) {
                if (s.startsWith("jdos.")) {
                    s = "/" + s.replace('.', '/') + ".class";
                    return Dosbox.class.getResource(s.substring(6));
                }
                return null;
            }

            public void close() {
            }
        });
    }

    static private int count;

    static private raster_info compileMethod(StringBuilder method, raster_info info) {
        //logger.log(Level.DEBUG,method.toString());
        try {
            String className = "Rasterizer" + (count++);

            CtClass codeBlock = pool.makeClass(className);
            codeBlock.setSuperclass(pool.getCtClass("jdos.hardware.mame.raster_info"));
            codeBlock.addInterface(pool.getCtClass("jdos.hardware.mame.poly_draw_scanline_func"));
            method.append("}");
            CtMethod m = CtNewMethod.make("public void call(short[] dest, int destOffset, int y, poly_extent extent, poly_extra_data extra, int threadid) {" + method, codeBlock);
            codeBlock.addMethod(m);

            String constructor =
                    "{this.eff_color_path = " + info.eff_color_path + ";" +
                            "this.eff_alpha_mode = " + info.eff_alpha_mode + ";" +
                            "this.eff_fog_mode = " + info.eff_fog_mode + ";" +
                            "this.eff_fbz_mode = " + info.eff_fbz_mode + ";" +
                            "this.eff_tex_mode_0 = " + info.eff_tex_mode_0 + ";" +
                            "this.eff_tex_mode_1 = " + info.eff_tex_mode_1 + ";" +
                            "this.callback = this;}";

            CtConstructor c = CtNewConstructor.make("public " + className + "()" + constructor, codeBlock);
            codeBlock.addConstructor(c);

            // Make the dynamic class belong to its own class loader so that when we
            // release the raster block the class and class loader will be unloaded
            URLClassLoader cl = (URLClassLoader) codeBlock.getClass().getClassLoader();
            cl = URLClassLoader.newInstance(cl.getURLs(), cl);
            Class<?> clazz = codeBlock.toClass(cl, null);
            raster_info result = (raster_info) clazz.newInstance();
            if (saveClasses) {
                savedClasses.add(new SaveInfo(info, codeBlock.toBytecode()));
            }
            codeBlock.detach();
            return result;
        } catch (Exception e) {
            logger.log(Level.DEBUG, method.toString());
            logger.log(Level.ERROR, e.getMessage(), e);
        }
        return null;
    }
}
