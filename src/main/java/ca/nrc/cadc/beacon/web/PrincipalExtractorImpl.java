/*
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2016.                            (c) 2016.
 *  Government of Canada                 Gouvernement du Canada
 *  National Research Council            Conseil national de recherches
 *  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
 *  All rights reserved                  Tous droits réservés
 *
 *  NRC disclaims any warranties,        Le CNRC dénie toute garantie
 *  expressed, implied, or               énoncée, implicite ou légale,
 *  statutory, of any kind with          de quelque nature que ce
 *  respect to the software,             soit, concernant le logiciel,
 *  including without limitation         y compris sans restriction
 *  any warranty of merchantability      toute garantie de valeur
 *  or fitness for a particular          marchande ou de pertinence
 *  purpose. NRC shall not be            pour un usage particulier.
 *  liable in any event for any          Le CNRC ne pourra en aucun cas
 *  damages, whether direct or           être tenu responsable de tout
 *  indirect, special or general,        dommage, direct ou indirect,
 *  consequential or incidental,         particulier ou général,
 *  arising from the use of the          accessoire ou fortuit, résultant
 *  software.  Neither the name          de l'utilisation du logiciel. Ni
 *  of the National Research             le nom du Conseil National de
 *  Council of Canada nor the            Recherches du Canada ni les noms
 *  names of its contributors may        de ses  participants ne peuvent
 *  be used to endorse or promote        être utilisés pour approuver ou
 *  products derived from this           promouvoir les produits dérivés
 *  software without specific prior      de ce logiciel sans autorisation
 *  written permission.                  préalable et particulière
 *                                       par écrit.
 *
 *  This file is part of the             Ce fichier fait partie du projet
 *  OpenCADC project.                    OpenCADC.
 *
 *  OpenCADC is free software:           OpenCADC est un logiciel libre ;
 *  you can redistribute it and/or       vous pouvez le redistribuer ou le
 *  modify it under the terms of         modifier suivant les termes de
 *  the GNU Affero General Public        la “GNU Affero General Public
 *  License as published by the          License” telle que publiée
 *  Free Software Foundation,            par la Free Software Foundation
 *  either version 3 of the              : soit la version 3 de cette
 *  License, or (at your option)         licence, soit (à votre gré)
 *  any later version.                   toute version ultérieure.
 *
 *  OpenCADC is distributed in the       OpenCADC est distribué
 *  hope that it will be useful,         dans l’espoir qu’il vous
 *  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
 *  without even the implied             GARANTIE : sans même la garantie
 *  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
 *  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
 *  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
 *  General Public License for           Générale Publique GNU Affero
 *  more details.                        pour plus de détails.
 *
 *  You should have received             Vous devriez avoir reçu une
 *  a copy of the GNU Affero             copie de la Licence Générale
 *  General Public License along         Publique GNU Affero avec
 *  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
 *  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
 *                                       <http://www.gnu.org/licenses/>.
 *
 *
 ************************************************************************
 */

package ca.nrc.cadc.beacon.web;

import ca.nrc.cadc.auth.*;
import ca.nrc.cadc.net.NetUtil;
import ca.nrc.cadc.util.StringUtil;
import org.restlet.Request;
import org.restlet.data.Cookie;
import org.restlet.data.Header;
import org.restlet.util.Series;

import java.io.IOException;
import java.security.AccessControlException;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PrincipalExtractorImpl implements PrincipalExtractor
{
    private final Request request;

    private X509CertificateChain chain;
    private DelegationToken token;
    private SSOCookieCredential cookieCredential;
    private Principal cookiePrincipal;


    public PrincipalExtractorImpl(final Request request)
    {
        this.request = request;
    }


    @SuppressWarnings("unchecked")
    void init()
    {
        if (chain == null)
        {
            final List<X509Certificate> clientCertificates =
                    (List<X509Certificate>) request.getAttributes().get(
                            "org.restlet.https.clientCertificates");
            if (clientCertificates != null && !clientCertificates.isEmpty())
            {
                chain = new X509CertificateChain(clientCertificates);
            }
        }

        if (token == null)
        {
            final Series<Header> headers =
                    (Series<Header>) request.getAttributes().
                            get("org.restlet.http.headers");
            final String tokenValue =
                    headers.getFirstValue("X-CADC-DelegationToken");
            if (StringUtil.hasText(tokenValue))
            {
                try
                {
                    token = DelegationToken
                            .parse(tokenValue, request.getResourceRef()
                                    .getPath());
                }
                catch (InvalidDelegationTokenException | RuntimeException e)
                {
                    throw new AccessControlException(
                            "Invalid delegation token");
                }
            }
        }

        for (final Cookie ssoCookie : request.getCookies())
        {
            if ("CADC_SSO".equals(ssoCookie.getName()) && StringUtil
                    .hasText(ssoCookie.getValue()))
            {
                SSOCookieManager ssoCookieManager = new SSOCookieManager();

                try
                {
                    cookiePrincipal = ssoCookieManager
                            .parse(ssoCookie.getValue());
                    cookieCredential =
                            new SSOCookieCredential(
                                    ssoCookie.getValue(),
                                    NetUtil.getDomainName(request.
                                            getResourceRef().toUrl()));
                }
                catch (IOException | InvalidDelegationTokenException e)
                {
                    System.out.println("Cannot use SSO Cookie. Reason: "
                                       + e.getMessage());
                }
            }
        }
    }


    @Override
    public Set<Principal> getPrincipals()
    {
        init();
        final Set<Principal> principals = new HashSet<>();

        addHTTPPrincipal(principals);
        addX500Principal(principals);

        return principals;
    }

    @Override
    public X509CertificateChain getCertificateChain()
    {
        return chain;
    }

    @Override
    public DelegationToken getDelegationToken()
    {
        return token;
    }

    @Override
    public SSOCookieCredential getSSOCookieCredential()
    {
        return cookieCredential;
    }

    private void addHTTPPrincipal(Set<Principal> principals)
    {
        final String httpUser = getAuthenticatedUsername();

        if (StringUtil.hasText(httpUser))
        {
            principals.add(new HttpPrincipal(httpUser));
        }
        else if (cookiePrincipal != null)
        {
            principals.add(cookiePrincipal);
        }
        else if (token != null)
        {
            principals.add(token.getUser());
        }

    }

    private void addX500Principal(Set<Principal> principals)
    {
        if (chain != null)
        {
            principals.add(chain.getPrincipal());
        }
    }

    protected String getAuthenticatedUsername()
    {
        final List<Principal> clientPrincipals =
                request.getClientInfo().getPrincipals();

        return clientPrincipals.isEmpty() ? null
                                          : clientPrincipals.get(0).getName();
    }
}
